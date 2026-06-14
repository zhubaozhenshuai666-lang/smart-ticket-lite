# JMeter 异步下单与异步创单吞吐压测手册

这份文档只讲一件事：用 JMeter 正式测试抢票链路的入口吞吐和异步创单吞吐。轻量脚本不能冒充正式压测，高并发测试交付物必须有标准测试计划、参数化数据、可复现命令和可阅读报告。

## 1. 文件清单

本次压测文件：

- `scripts/jmeter/async-order-throughput.jmx`：JMeter 测试计划。
- `scripts/jmeter/data/order-users.csv`：用户、活动、票档、入场 token 参数模板。

JMeter 测试计划覆盖这些动作：

1. 从 CSV 读取用户 JWT、演出、场次、票档、购买数量、等待室 token。
2. 调用 `/api/orders/idempotency-token` 获取幂等 token。
3. 调用 `/api/orders/async` 提交异步下单。
4. 提取 `requestId`。
5. 轮询 `/api/order-requests/{requestId}`，直到进入 `SUCCESS`、`FAILED`、`CANCELLED`、`COMPENSATED` 终态。
6. 通过 JMeter 聚合报告查看吞吐、错误率、平均响应时间、P90、P95、P99。

## 2. 先讲清楚：你到底在测什么

JMeter 报告里会出现多个请求：

- `GET /api/orders/idempotency-token`
- `POST /api/orders/async`
- `GET /api/order-requests/{requestId}`
- `异步下单完整链路`

严格看指标时，口径如下：

- 看入口提交能力：看 `POST /api/orders/async` 的 Throughput、Error%、P95、P99。
- 看用户完整等待体验：看 `异步下单完整链路` 的响应时间分位数。
- 看异步创单是否跟得上：看 `GET /api/order-requests/{requestId}` 的轮询次数、终态断言失败数、后台 MQ 和订单创建指标。

不要只看总 Throughput。因为轮询请求会放大 HTTP 请求数，总 Throughput 不是下单 QPS。

## 3. 压测前注意事项

第一，禁止压生产。没有隔离活动、隔离库存、隔离用户、回滚方案和授权，就不要碰生产。

第二，库存必须足够。库存不足时，大量请求会业务失败，测出来的是售罄失败速度，不是系统吞吐。

第三，正式压测不要只用一个用户。项目里有幂等、风控、限流、等待室和可能的一人一单约束，单用户压测会污染结论。

第四，等待室开启时，CSV 里的 `admissionToken` 必须是真实可消费 token。留空会被 `WaitingRoomService.consumeAdmissionToken` 拒绝。

第五，JMeter GUI 只能用来编辑和冒烟，不要用 GUI 做正式大压测。正式压测必须用命令行 non-GUI 模式。

第六，单台压测机也有极限。线程数上千以后，压测机 CPU、网卡、端口、文件句柄都可能先成为瓶颈。要测 10000 级并发，应准备 JMeter 分布式压测。

第七，压测必须阶梯加压。不要一上来 10000 线程，先跑 10、100、500、1000，确认链路没有硬错误再继续。

## 4. 环境准备

### 4.1 安装 JMeter

macOS 可用 Homebrew：

```bash
brew install jmeter
```

检查版本：

```bash
jmeter --version
```

建议使用 JMeter 5.6.x。版本太老可能不兼容 JSON Extractor 或 Groovy 表达式。

### 4.2 启动项目依赖

确保这些组件已启动：

- MySQL
- Redis
- RabbitMQ
- smart-ticket-lite 应用

建议使用抢票配置启动应用：

```bash
SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

默认 JMeter 计划访问：

```text
http://127.0.0.1:8081
```

如果你的端口不同，运行时用 `-JHOST`、`-JPORT` 覆盖。

### 4.3 准备 CSV 数据

编辑：

```bash
scripts/jmeter/data/order-users.csv
```

格式：

```csv
authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken,idempotencyToken
替换成用户JWT不要带Bearer,1,1,2,1,,
```

字段解释：

- `authToken`：用户 JWT，不要带 `Bearer`。
- `showId`：演出 ID。
- `sessionId`：场次 ID。
- `ticketCategoryId`：票档 ID。
- `quantity`：购票数量。
- `admissionToken`：等待室入场 token；等待室关闭时可留空。
- `idempotencyToken`：默认留空，JMeter 会动态调用接口获取。只有你明确要用预生成幂等 token 时才填写。

正式压测时应该准备多行用户数据，不要一行用户循环压到底。

### 4.4 预热元数据

压测前执行：

```bash
curl -sS -X POST \
  -H "Authorization: Bearer 你的JWT" \
  http://127.0.0.1:8081/api/admin/ops/metadata-prewarm/order-submit
```

目的：提前把演出、场次、票档关系加载进应用缓存，避免压测第一波请求被数据库查询拖慢。

### 4.5 检查容量配置

执行：

```bash
curl -sS \
  -H "Authorization: Bearer 你的JWT" \
  http://127.0.0.1:8081/api/admin/ops/capacity/order-pipeline
```

重点看：

- `fastPipelineEnabled`：应为 `true`。
- `waitingRoomEnabled`：正式抢票洪峰应为 `true`。
- `directRabbitWaitForConfirm`：高吞吐入口不应等待 MQ confirm。
- `perOrderTimeoutDelayMessageEnabled`：如果为 `true`，每单都会增加超时消息写放大。
- `hardBottleneck`：如果提示 Outbox、单队列、单表热点，压测结果会被硬瓶颈限制。

## 5. GUI 冒烟测试

只用 GUI 做小流量检查：

```bash
jmeter -t scripts/jmeter/async-order-throughput.jmx
```

打开后检查：

1. `用户变量` 里的 `HOST`、`PORT` 是否正确。
2. `CSV 用户与活动参数` 的文件路径是否正确。
3. `THREADS` 先设成 `2`。
4. `LOOP_COUNT` 先设成 `5`。
5. 点击运行。

冒烟通过标准：

- `GET /api/orders/idempotency-token` 成功。
- `POST /api/orders/async` 成功并提取到 `requestId`。
- `GET /api/order-requests/{requestId}` 最终进入终态。
- `异步创单终态断言` 没有大量失败。

## 6. 命令行正式压测

创建结果目录：

```bash
mkdir -p /tmp/smart-ticket-jmeter
```

### 6.1 基线压测

```bash
jmeter -n \
  -t scripts/jmeter/async-order-throughput.jmx \
  -l /tmp/smart-ticket-jmeter/order-c50.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-c50 \
  -JTHREADS=50 \
  -JRAMP_SECONDS=30 \
  -JLOOP_COUNT=20 \
  -JHOST=127.0.0.1 \
  -JPORT=8081 \
  -JCSV_FILE=scripts/jmeter/data/order-users.csv
```

这轮总提交量约为：

```text
THREADS * LOOP_COUNT = 50 * 20 = 1000 单
```

### 6.2 阶梯加压

建议顺序：

| 轮次 | THREADS | LOOP_COUNT | 约提交量 | 目的 |
| --- | ---: | ---: | ---: | --- |
| 1 | 10 | 10 | 100 | 冒烟 |
| 2 | 50 | 20 | 1000 | 基线 |
| 3 | 100 | 50 | 5000 | 观察入口 RT |
| 4 | 300 | 50 | 15000 | 观察 MQ 和消费者 |
| 5 | 500 | 100 | 50000 | 单机高压 |
| 6 | 1000 | 100 | 100000 | 压测机也可能成为瓶颈 |

示例：

```bash
jmeter -n \
  -t scripts/jmeter/async-order-throughput.jmx \
  -l /tmp/smart-ticket-jmeter/order-c300.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-c300 \
  -JTHREADS=300 \
  -JRAMP_SECONDS=120 \
  -JLOOP_COUNT=50 \
  -JHOST=127.0.0.1 \
  -JPORT=8081 \
  -JCSV_FILE=scripts/jmeter/data/order-users.csv
```

### 6.3 只测入口提交

如果只想测 `/api/orders/async` 入口，不想让轮询请求影响 HTTP 总吞吐：

```bash
jmeter -n \
  -t scripts/jmeter/async-order-throughput.jmx \
  -l /tmp/smart-ticket-jmeter/order-submit-only.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-submit-only \
  -JTHREADS=300 \
  -JRAMP_SECONDS=120 \
  -JLOOP_COUNT=50 \
  -JPOLL_RESULT=false
```

注意：这个模式只能证明入口接收能力，不能证明异步创单能力。

## 7. 报告怎么看

打开 HTML 报告：

```bash
open /tmp/smart-ticket-jmeter/report-c50/index.html
```

重点看这些页面：

- Dashboard 首页：整体 Throughput、Error、响应时间概览。
- Statistics：每个 sampler 的平均、P90、P95、P99、Throughput、Error%。
- Response Times Percentiles：响应时间分位数。
- Transactions per Second：吞吐曲线。
- Response Codes per Second：错误码曲线。

核心 sampler 判断：

- `POST /api/orders/async`：入口下单提交能力。
- `异步下单完整链路`：用户从提交到异步终态的完整体验。
- `GET /api/order-requests/{requestId}`：轮询结果接口压力，不等于下单 QPS。

## 8. 性能指标怎么判

入口提交能力看：

- `POST /api/orders/async` Throughput。
- `POST /api/orders/async` Error%。
- `POST /api/orders/async` P95、P99。

异步创单能力看：

- `异步下单完整链路` P95、P99。
- `异步创单终态断言` 失败数量。
- 后台 `orderCreatedCount` 增量。
- RabbitMQ 队列积压。
- MySQL 慢 SQL。

合格线建议：

- 计划流量内 `POST /api/orders/async` Error% 小于 `1%`。
- `POST /api/orders/async` P99 不持续恶化。
- `异步下单完整链路` 绝大多数能在业务允许时间内进入终态。
- `localMessageDeadCount` 增量必须为 `0`。
- `stockCompensationFailedCount` 增量必须为 `0`。
- MQ 队列不能无限增长。

## 9. 运维指标必须同步看

压测前后分别执行：

```bash
curl -sS \
  -H "Authorization: Bearer 你的JWT" \
  http://127.0.0.1:8081/api/admin/ops/metrics-summary
```

重点看增量：

- `asyncOrderRequestSuccessCount`
- `asyncOrderRequestFailedCount`
- `orderCreatedCount`
- `rateLimitRejectedCount`
- `soldoutFastfailCount`
- `localMessageFailedCount`
- `localMessageDeadCount`
- `deadLetterPendingCount`
- `stockConsistencyPendingCount`
- `stockCompensationFailedCount`

严厉标准：

- `localMessageDeadCount` 增长，说明可靠消息链路有硬伤。
- `stockCompensationFailedCount` 增长，说明库存补偿不可靠。
- `deadLetterPendingCount` 持续增长，说明消费者或延迟关闭链路有问题。
- `asyncOrderRequestSuccessCount` 高但 `orderCreatedCount` 低，说明入口接住了，后端没消化。

## 10. 常见问题定位

| 现象 | 判断 | 优先检查 |
| --- | --- | --- |
| `POST /api/orders/async` P99 很高 | 入口链路扛不住 | 鉴权、限流 Lua、Redis RTT、应用线程池、元数据缓存 |
| `POST /api/orders/async` 成功，完整链路 P99 很高 | 后端异步处理慢 | RabbitMQ 积压、消费者数量、DB 写入 |
| `异步创单终态断言` 大量失败 | 请求没有按时进入终态 | MQ、消费者异常、DB 慢 SQL、库存不足 |
| `GET /api/orders/idempotency-token` 慢 | token 生成影响压测 | 抢票页应提前预取 token，正式压测可用 CSV 预生成 token |
| 大量 401/403 | 用户 token 错误 | CSV 的 `authToken` |
| 大量等待室失败 | admissionToken 错误或为空 | 等待室 token 生成、过期时间、CSV 字段 |
| 大量库存不足 | 测试数据错误或库存耗尽 | Redis 库存、DB 库存桶、压测提交量 |

## 11. 分布式压测建议

单机 JMeter 不适合硬压 10000 并发。要严肃测 10000 并发，至少准备：

- 1 台 JMeter Controller。
- 多台 JMeter Worker。
- 每台 Worker 控制线程数，避免单机 CPU 或网卡打满。
- 所有 Worker 使用同版本 JMeter、同一份 CSV 数据。
- 压测机和被测服务不要部署在同一台机器。

否则你看到的瓶颈可能是 JMeter，不是下单系统。

## 12. 最终结论怎么写

不要写“系统支持 10000 并发”这种空话。压测结论必须包含：

- 压测环境规格。
- JMeter 线程数、Ramp-Up、循环次数。
- 活动、票档、库存规模。
- 用户 token 数量。
- 是否开启等待室。
- `POST /api/orders/async` Throughput、P95、P99、Error%。
- `异步下单完整链路` P95、P99、Error%。
- `orderCreatedCount` 增量。
- MQ 最大积压和恢复时间。
- MySQL 慢 SQL 情况。
- Redis 慢日志情况。
- 失败原因 TopN。

没有这些数据，就不能下吞吐结论。
