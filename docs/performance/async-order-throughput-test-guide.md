# 异步下单与异步创单吞吐压测手册

这份文档只讲一件事：怎样用项目里的脚本测试抢票链路的吞吐，并且看懂结果。不要把“接口提交成功 QPS”和“最终创建订单 TPS”混为一谈，前者只能说明入口扛住了，后者才接近真实订单链路能力。

## 1. 本次压测测什么

脚本位置：

```bash
scripts/load/async_order_throughput_test.py
```

它会按顺序做这些事：

1. 调用 `/api/admin/ops/capacity/order-pipeline` 检查当前抢票链路配置。
2. 调用 `/api/admin/ops/metrics-summary` 记录压测前运维指标。
3. 批量调用 `/api/orders/idempotency-tokens` 预取幂等 token。
4. 并发调用 `/api/orders/async` 提交异步下单请求。
5. 轮询 `/api/order-requests/{requestId}`，直到请求进入 `SUCCESS`、`FAILED`、`CANCELLED`、`COMPENSATED` 等终态。
6. 再次调用 `/api/admin/ops/metrics-summary`，输出关键指标增量。

最终你会得到两类指标：

- `submit_qps`：入口接口接收请求的速度。
- `created_order_tps`：异步请求真正变成成功订单的速度。

严格说，系统对外宣传能力时不能只看 `submit_qps`。如果入口 10000 QPS，但消费者、MQ、数据库只能每秒创单 800 单，那真实订单吞吐就是 800 TPS，剩下的是排队和积压。

## 2. 压测前必须知道的注意事项

第一，禁止直接压生产环境。除非你明确有授权、隔离活动、隔离库存、隔离用户和回滚方案，否则这就是事故。

第二，压测前必须准备足够库存。库存不够时，失败原因会集中变成库存不足，测出来的是业务失败速度，不是系统吞吐上限。

第三，抢票压测不能只用一个用户长期压。项目里有幂等、限流、风控、等待室和可能的“一人一单”规则，单用户压测会被业务规则干扰。单用户可以做冒烟测试，正式吞吐测试要准备多用户 token 或放宽本地规则。

第四，幂等 token 预取不计入 `submit_qps`。真实抢票页也应该提前下发 token，点击瞬间再取 token 会把入口 HTTP 压力放大。

第五，等待室开启时，真实请求需要 `admissionToken`。如果你本地只是测核心链路，可以加 `--skip-capacity-guard` 做摸底；如果你要测完整等待室链路，就要准备入场 token 文件。

第六，单机压测机本身会成为瓶颈。Python 脚本适合本地和单机摸底；要压到几千甚至上万并发，应使用多台压测机，或者改用 k6、JMeter、Gatling 这类专门工具。

第七，先小流量验证，再逐步加压。不要一上来 `10000` 并发，先确认接口、库存、token、等待室和消费者都正常。

## 3. 环境准备

### 3.1 启动依赖

你需要保证这些组件已经启动并且应用能连上：

- MySQL
- Redis
- RabbitMQ
- smart-ticket-lite 应用

建议本地使用抢票配置启动：

```bash
SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

如果你的本地端口不是 `8081`，后续命令把 `--base-url` 改成实际地址。

### 3.2 准备登录 token

脚本需要用户登录 JWT：

```bash
export AUTH_TOKEN="替换成你的用户JWT"
```

不要带 `Bearer ` 前缀，脚本会自动加。

### 3.3 检查容量配置

执行：

```bash
curl -sS \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  http://127.0.0.1:8081/api/admin/ops/capacity/order-pipeline
```

重点看这些字段：

- `fastPipelineEnabled`：应该是 `true`。否则说明入口不是高并发快速链路。
- `waitingRoomEnabled`：正式洪峰场景应该是 `true`。否则洪峰会直接打核心链路。
- `directRabbitWaitForConfirm`：高吞吐入口通常不应该等待 MQ confirm。
- `perOrderTimeoutDelayMessageEnabled`：如果是 `true`，每单都会多一条超时消息，吞吐会被写放大拖住。
- `hardBottleneck`：如果提示 Outbox、单队列、单表热点等问题，压测结果会被这些瓶颈限制。

脚本默认会做容量守卫检查，不满足就直接失败。你本地摸底时可以加：

```bash
--skip-capacity-guard
```

但要清楚：跳过守卫后测出来的是“当前配置能力”，不是工业级抢票配置能力。

### 3.4 预热下单元数据

执行：

```bash
curl -sS -X POST \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  http://127.0.0.1:8081/api/admin/ops/metadata-prewarm/order-submit
```

这一步是为了让演出、场次、票档关系等元数据提前进入应用内存缓存，避免压测时第一批请求被数据库查询拖慢。

### 3.5 准备库存

你必须确认目标 `ticketCategoryId` 有足够可售库存。建议压测库存数量至少是 `TOTAL * QUANTITY` 的 1.2 倍。

如果库存不足，压测结果里会出现大量业务失败，这不是吞吐问题，是测试数据错误。

## 4. 最小冒烟测试

先跑 10 个请求：

```bash
python3 scripts/load/async_order_throughput_test.py \
  --base-url http://127.0.0.1:8081 \
  --auth-token "${AUTH_TOKEN}" \
  --total 10 \
  --concurrency 2 \
  --show-id 1 \
  --session-id 1 \
  --ticket-category-id 2 \
  --quantity 1 \
  --skip-capacity-guard
```

冒烟测试通过标准：

- `submit_failed=0`
- 没有大量 `terminal_timeout`
- `Terminal status distribution` 里能看到合理的终态
- `Metrics delta` 里没有 `localMessageDeadCount`、`stockCompensationFailedCount` 这类严重异常增长

## 5. 正式压测步骤

### 5.1 基线压测

先用低并发确认稳定性：

```bash
python3 scripts/load/async_order_throughput_test.py \
  --base-url http://127.0.0.1:8081 \
  --auth-token "${AUTH_TOKEN}" \
  --total 1000 \
  --concurrency 50 \
  --show-id 1 \
  --session-id 1 \
  --ticket-category-id 2 \
  --quantity 1 \
  --poll-timeout-seconds 60 \
  --output-json /tmp/async-order-1000-c50.json
```

### 5.2 阶梯加压

建议按下面顺序跑，不要跳级：

| 轮次 | total | concurrency | 目的 |
| --- | ---: | ---: | --- |
| 1 | 1000 | 50 | 基线稳定性 |
| 2 | 5000 | 100 | 观察入口 RT 和消费者积压 |
| 3 | 10000 | 300 | 找 MQ、Redis、DB 的第一瓶颈 |
| 4 | 30000 | 500 | 观察 p99 和终态超时 |
| 5 | 50000 | 1000 | 单机压测上限摸底 |

示例：

```bash
python3 scripts/load/async_order_throughput_test.py \
  --base-url http://127.0.0.1:8081 \
  --auth-token "${AUTH_TOKEN}" \
  --total 10000 \
  --concurrency 300 \
  --show-id 1 \
  --session-id 1 \
  --ticket-category-id 2 \
  --quantity 1 \
  --poll-timeout-seconds 120 \
  --output-json /tmp/async-order-10000-c300.json
```

### 5.3 只测入口提交

如果你只想看 `/api/orders/async` 接口接收能力，不想轮询结果：

```bash
python3 scripts/load/async_order_throughput_test.py \
  --base-url http://127.0.0.1:8081 \
  --auth-token "${AUTH_TOKEN}" \
  --total 10000 \
  --concurrency 300 \
  --show-id 1 \
  --session-id 1 \
  --ticket-category-id 2 \
  --quantity 1 \
  --skip-result-poll \
  --output-json /tmp/async-order-submit-only.json
```

注意：这个模式不能证明订单创建链路扛得住，只能证明入口能接多少。

## 6. 等待室 admissionToken 怎么测

如果等待室校验开启，正式提交订单需要请求体里带 `admissionToken`。脚本支持：

```bash
--admission-token-file /tmp/admission-tokens.txt
```

文件格式是一行一个 token：

```text
token-1
token-2
token-3
```

注意：`/api/admin/ops/waiting-room/admission-batches` 是释放已经排队用户的入场资格，不是凭空制造任意用户 token。正式压测需要先按用户进入等待室，再释放入场资格，再把得到的 token 写入文件。否则你测到的会是入场失败，不是下单吞吐。

## 7. 输出指标怎么看

脚本输出示例字段如下：

```text
submit_success=998 submit_failed=2 submit_elapsed=3.421s submit_qps=292.02
terminal_success=940 terminal_failed=40 terminal_timeout=18 poll_elapsed=25.314s created_order_tps=37.13
```

逐项解释：

- `submit_success`：入口成功返回 `requestId` 的数量。
- `submit_failed`：入口直接失败的数量，包括限流、风控、参数错误、库存快速失败等。
- `submit_qps`：入口提交吞吐。这个值高不代表最终订单创建快。
- `terminal_success`：异步处理后真正创建订单成功的数量。
- `terminal_failed`：异步处理进入失败终态的数量，例如库存不足、重复下单、入场资格无效。
- `terminal_timeout`：在 `poll-timeout-seconds` 时间内没有进入终态。这个值高，通常说明队列积压或消费者吞吐不足。
- `created_order_tps`：成功订单完成速度，比 `submit_qps` 更接近真实创单能力。
- `submit_latency.p95_ms`：入口接口 95 分位耗时。
- `submit_latency.p99_ms`：入口接口 99 分位耗时。这个值升高说明入口开始抖动。
- `terminal_latency.p99_ms`：从提交到异步终态的 99 分位耗时。它包含排队、消费、数据库写入等全部时间。

## 8. 运维指标怎么看

`Metrics delta` 是压测前后指标差值：

- `asyncOrderRequestSuccessCount`：异步请求成功受理数量。
- `asyncOrderRequestFailedCount`：异步请求失败数量。
- `orderCreatedCount`：创建订单数量。
- `rateLimitRejectedCount`：限流拒绝数量。压测计划内有入场削峰时，它增长不一定是坏事；无计划增长说明入口限流过紧或压测参数过猛。
- `soldoutFastfailCount`：库存售罄快速失败数量。压测库存不足时会暴涨。
- `localMessageFailedCount`：本地消息失败数量。出现增长就要查 MQ 或消息可靠性链路。
- `localMessageDeadCount`：本地消息死亡数量。这个不能容忍。
- `deadLetterPendingCount`：死信待处理数量。增长说明消费者、超时关闭或补偿存在问题。
- `stockConsistencyPendingCount`：库存一致性待处理数量。增长说明库存链路存在延迟或异常。
- `stockCompensationFailedCount`：库存补偿失败数量。这个是严重问题，不能当成普通压测噪声。

## 9. 常见问题定位

| 现象 | 严格判断 | 优先检查 |
| --- | --- | --- |
| `submit_qps` 低，`submit_latency.p99_ms` 高 | 入口链路扛不住 | 鉴权、限流 Lua、Redis RTT、元数据缓存、应用线程池 |
| `submit_qps` 高，`created_order_tps` 低 | 只是入口接住了，后端没消化 | MQ 积压、消费者数量、DB 写入、订单表索引 |
| `terminal_timeout` 高 | 请求卡在队列或消费者处理太慢 | RabbitMQ 队列深度、消费者日志、DB 慢 SQL |
| `terminal_failed` 高且原因是库存不足 | 测试数据错误或库存真的耗尽 | Redis 库存、DB 库存桶、压测 total |
| `rateLimitRejectedCount` 高 | 入场或限流拒绝了流量 | 令牌桶配置、等待室发放速度、目标 QPS |
| `localMessageDeadCount` 增长 | 消息可靠性链路有硬伤 | local_message 状态、MQ 连接、消费者异常 |
| `stockCompensationFailedCount` 增长 | 库存补偿不可靠 | 库存回滚 Lua、补偿任务、异常日志 |

## 10. 合格线怎么定

本地机器不要幻想测出大麦、猫眼级能力。合理的合格线应该按环境分。

开发机冒烟：

- `submit_failed=0`
- `terminal_timeout=0`
- `localMessageDeadCount` 增量为 `0`
- `stockCompensationFailedCount` 增量为 `0`

单机压测：

- 计划流量内入口成功率不低于 `99%`
- 异步请求在 `60-120` 秒内进入终态的比例不低于 `99%`
- `submit_latency.p99_ms` 不持续恶化
- `terminal_latency.p99_ms` 随并发上升可以变高，但不能无限堆积
- RabbitMQ、Redis、MySQL 不能出现不可恢复错误

准生产压测：

- 必须多用户、多压测机、隔离活动、隔离库存
- 必须记录应用 CPU、内存、GC、Redis 慢日志、MySQL 慢 SQL、MQ 队列深度
- 必须有停止压测和恢复数据方案

## 11. 脚本返回码

- 返回 `0`：入口提交没有失败，轮询模式下也没有终态超时。
- 返回 `1`：存在入口失败或终态超时。
- 返回 `2`：参数错误，例如没有传 `AUTH_TOKEN`。
- 返回 `130`：手动中断。

返回 `0` 不代表系统已经具备高并发能力，只代表这轮压测没有触发脚本定义的明显失败。真正结论必须结合 QPS、TPS、p99、MQ 积压、DB 慢 SQL 和运维指标一起判断。

