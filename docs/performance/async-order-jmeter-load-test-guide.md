# 异步下单 JMeter 压测傻瓜文档

这份文档只讲一件事：用 JMeter 压 `/api/orders/async`，并轮询 `/api/order-requests/{requestId}`，看入口提交能力和 Kafka 消费创单能力。

不要拿同步下单 `/api/orders` 做主压测。这个系统现在的高并发主链路是异步提交、Kafka 消费、后台创单。

## 1. 文件位置

- JMeter 测试计划：`scripts/jmeter/async-order-load-test.jmx`
- 压测数据模板：`scripts/jmeter/data/async-order-users.csv`
- 命令行启动脚本：`scripts/load/run-async-order-jmeter.sh`
- 压测报告输出：默认在 `reports/jmeter/{时间}/`

## 2. 先装 JMeter

macOS：

```bash
brew install jmeter
jmeter -v
```

如果不是 Homebrew 安装，自己设置：

```bash
export JMETER_BIN=/你的路径/apache-jmeter/bin/jmeter
```

## 3. 启动被压测系统

必须保证 MySQL、Redis、Kafka 都已经启动。只启动 Spring Boot 没用，Kafka 没起来就只能测到“提交失败”。

建议显式启用抢票 profile：

```bash
SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

如果你只是第一次跑通压测，建议先临时关闭等待室：

```bash
SMART_TICKET_WAITING_ROOM_ENABLED=false SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

原因很简单：等待室打开时，每个下单请求都要有合法 `admissionToken`。你 CSV 里没有 token，请求会被正常拦掉，这不是压测脚本坏了。

## 4. 准备用户 CSV

复制模板：

```bash
cp scripts/jmeter/data/async-order-users.csv /tmp/async-order-users.csv
```

编辑 `/tmp/async-order-users.csv`：

```csv
authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken
用户JWT去掉Bearer,1,1,2,1,
另一个用户JWT去掉Bearer,1,1,2,1,
```

字段解释：

- `authToken`：用户 JWT，不要写 `Bearer ` 前缀，脚本会自动加。
- `showId`：演出 ID。
- `sessionId`：场次 ID。
- `ticketCategoryId`：票档 ID。
- `quantity`：每次买几张。
- `admissionToken`：等待室放行 token。等待室关闭时留空；等待室开启时必须填。

CSV 里用户数必须足够。你拿 1 个用户压 500 QPS，结果会被用户限流和幂等策略打爆，数据没有意义。

## 5. 先跑 30 秒冒烟

```bash
BASE_URL=http://127.0.0.1:8081 \
DATA_FILE=/tmp/async-order-users.csv \
THREADS=20 \
RAMP_SECONDS=10 \
DURATION_SECONDS=30 \
TARGET_QPS=20 \
POLL_RESULT=true \
scripts/load/run-async-order-jmeter.sh
```

这一步只看能不能跑通，不看性能结论。失败就先修环境，不要继续加 QPS。

## 6. 正式压测命令

入口提交加结果轮询：

```bash
BASE_URL=http://127.0.0.1:8081 \
DATA_FILE=/tmp/async-order-users.csv \
THREADS=300 \
RAMP_SECONDS=60 \
DURATION_SECONDS=300 \
TARGET_QPS=500 \
POLL_RESULT=true \
POLL_MAX_ATTEMPTS=30 \
POLL_INTERVAL_MS=300 \
scripts/load/run-async-order-jmeter.sh
```

只压入口提交，不轮询结果：

```bash
BASE_URL=http://127.0.0.1:8081 \
DATA_FILE=/tmp/async-order-users.csv \
THREADS=300 \
RAMP_SECONDS=60 \
DURATION_SECONDS=300 \
TARGET_QPS=1000 \
POLL_RESULT=false \
scripts/load/run-async-order-jmeter.sh
```

只压入口没有意义，但它能帮你拆分问题：入口能扛，端到端不行，瓶颈多半在 Kafka 消费、Redis/MySQL、创单事务或消费者并发。

## 7. 参数说明

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `BASE_URL` | `http://127.0.0.1:8081` | 被压测服务地址 |
| `DATA_FILE` | `scripts/jmeter/data/async-order-users.csv` | 用户 CSV |
| `THREADS` | `100` | JMeter 并发线程数 |
| `RAMP_SECONDS` | `60` | 多少秒内把线程升满 |
| `DURATION_SECONDS` | `300` | 压测持续时间 |
| `TARGET_QPS` | `200` | 目标入口请求 QPS |
| `TARGET_QPM` | 自动计算 | JMeter 内部使用的每分钟目标吞吐，不需要手填 |
| `POLL_RESULT` | `true` | 是否轮询 Kafka 创单结果 |
| `POLL_MAX_ATTEMPTS` | `20` | 每个请求最多轮询几次 |
| `POLL_INTERVAL_MS` | `300` | 每次轮询间隔 |
| `RISK_DECISION` | `pass` | 风控网关注入的决策头 |
| `DO_PREWARM` | `false` | 是否先调用元数据预热接口 |
| `ADMIN_TOKEN` | 空 | 调 admin 预热接口时需要 |

## 8. 脚本实际做了什么

每个虚拟用户循环执行：

1. `GET /api/orders/idempotency-token`
2. 从响应 `$.data.token` 提取幂等 token
3. `POST /api/orders/async`
4. 从响应 `$.data.requestId` 提取异步请求 ID
5. 如果 `POLL_RESULT=true`，循环请求 `GET /api/order-requests/{requestId}`
6. 遇到 `SUCCESS`、`FAILED`、`COMPENSATED`、`CANCELLED` 就停止轮询

这里的 Kafka 不需要 JMeter 直接连。JMeter 打的是 HTTP 入口；系统收到请求后写 Kafka；消费者消费 Kafka 后创建订单；JMeter 再通过结果查询接口观察处理完成。

## 9. 看报告

跑完后终端会输出：

```text
HTML 报告：reports/jmeter/20260619-xxxxxx/html/index.html
原始结果：reports/jmeter/20260619-xxxxxx/result.jtl
JMeter 日志：reports/jmeter/20260619-xxxxxx/jmeter.log
```

重点看 HTML 报告里的：

- `02 提交异步下单请求`：入口提交耗时、错误率、吞吐。
- `03 查询异步请求结果`：异步处理完成速度。这个指标受 Kafka 消费、Redis、MySQL 创单事务影响。
- Error 百分比：不允许只看平均响应时间。
- 95%、99% 响应时间：平均值没什么用，高并发系统要看尾延迟。

## 10. 怎么判断瓶颈

- `01 获取下单幂等 Token` 慢：Redis/JWT/应用线程池可能有问题。
- `02 提交异步下单请求` 慢：入口限流 Lua、Redis 预扣、等待室、应用线程池可能有问题。
- `02` 很快但 `03` 长时间不成功：Kafka 消费能力、消费者并发、MySQL 事务、库存表锁竞争可能有问题。
- `03` 大量 `FAILED`：看响应里的 `failReason`，大概率是库存不足、等待室 token 无效、重复提交、限流或创单事务失败。
- JMeter 本机 CPU 打满：压测机先不够了，结论作废。

## 11. 等待室开启时怎么压

等待室开启后，CSV 的 `admissionToken` 不能空。

流程是：

1. 用户先调 `POST /api/waiting-room/queue?ticketCategoryId=票档ID` 进入等待室。
2. 管理端调 `POST /api/admin/ops/waiting-room/admission-batches?ticketCategoryId=票档ID&count=数量` 放行。
3. 把返回的放行 token 填进 CSV 的 `admissionToken` 列。
4. 再跑 JMeter。

如果你没有准备 admission token，却又开着等待室，异步提交失败是正确结果。

## 12. 压测纪律

- 先 20 QPS 跑通，再 100、300、500、1000 逐步加。
- 每档至少跑 5 分钟，10 秒的结果不能做容量结论。
- 每次只改一个参数。不要同时改线程数、Kafka 分区、消费者并发、bucket 数，否则你不知道是谁起作用。
- 压测前清理旧订单请求数据，或者至少记录起止时间，别把历史数据混进去。
- 压测期间盯 Kafka lag、Redis CPU、MySQL 慢 SQL、应用 GC、机器 CPU 和网卡。

## 13. 常见错误

`获取幂等 Token 失败`：
JWT 不对、用户不存在、认证拦截没过。

`异步提交失败`：
看响应体。常见原因是等待室 token 空、限流、库存不足、幂等 token 无效。

`JMeter 吞吐达不到 TARGET_QPS`：
线程数太少、JMeter 本机资源不够、接口响应太慢、网络不够。先提高 `THREADS`，再看压测机 CPU。

`HTML 报告生成失败`：
同一个输出目录已经存在且不为空。runner 默认用时间戳目录，正常不会撞；如果你手动指定 `RUN_ID`，自己换一个。
