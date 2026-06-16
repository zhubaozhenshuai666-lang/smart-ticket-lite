# JMeter 压测傻瓜式教学文档

这份文档按 0 基础写。照着做，不要跳步。

## 1. 你要先知道自己在测什么

本项目高并发主链路是：

```text
获取幂等 token -> 异步提交下单 -> Kafka 消费创建订单 -> 查询异步结果
```

对应接口：

```text
GET  /api/orders/idempotency-token
POST /api/orders/async
GET  /api/order-requests/{requestId}
```

不要压 `POST /api/orders`。那个是旧同步接口，不代表现在的抢票主链路。

## 2. 安装 JMeter

macOS 推荐：

```bash
brew install jmeter
```

检查是否安装成功：

```bash
jmeter --version
```

看到版本号就行，建议是 5.6.x。

## 3. 启动项目

先保证 MySQL、Redis、Kafka 都已经启动。然后在项目根目录启动应用：

```bash
SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

默认脚本访问：

```text
http://127.0.0.1:8081
```

如果你的项目不是 8081 端口，后面命令里改 `-JPORT`。

## 4. 准备用户 token

JMeter 需要登录后的 JWT。你可以用登录接口拿：

```bash
curl -sS -X POST http://127.0.0.1:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"你的手机号","password":"你的密码"}'
```

返回里找到 token。填到：

```text
jmeter/data/order-users.csv
```

格式如下：

```csv
authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken,idempotencyToken
你的JWT不要带Bearer,1,1,2,1,,
```

注意：

- `authToken` 不要写 `Bearer ` 前缀。
- `showId`、`sessionId`、`ticketCategoryId` 要换成你数据库里真实存在的数据。
- 等待室没开时，`admissionToken` 可以空着。
- `idempotencyToken` 默认空着，JMeter 会自动请求。
- 正式压测不要只放一行用户，多准备几十行更靠谱。

## 5. 先用 GUI 冒烟

GUI 只用来确认脚本能跑，不要用 GUI 做大压测。

```bash
jmeter -t jmeter/standard-async-order-load-test.jmx
```

打开后先检查：

- `全局变量` 里的 `HOST` 是不是 `127.0.0.1`
- `PORT` 是不是你的应用端口
- `CSV_FILE` 是不是 `jmeter/data/order-users.csv`
- `THREADS` 先设成 `2`
- `LOOP_COUNT` 先设成 `3`

点运行。没有大量红色失败，才进入下一步。

## 6. 命令行正式压测

创建结果目录：

```bash
mkdir -p /tmp/smart-ticket-jmeter
```

先跑低压冒烟：

```bash
jmeter -n \
  -t jmeter/standard-async-order-load-test.jmx \
  -l /tmp/smart-ticket-jmeter/smoke.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-smoke \
  -JTHREADS=2 \
  -JRAMP_SECONDS=5 \
  -JLOOP_COUNT=3 \
  -JHOST=127.0.0.1 \
  -JPORT=8081 \
  -JCSV_FILE=jmeter/data/order-users.csv
```

再跑基线：

```bash
jmeter -n \
  -t jmeter/standard-async-order-load-test.jmx \
  -l /tmp/smart-ticket-jmeter/c50.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-c50 \
  -JTHREADS=50 \
  -JRAMP_SECONDS=30 \
  -JLOOP_COUNT=20 \
  -JHOST=127.0.0.1 \
  -JPORT=8081 \
  -JCSV_FILE=jmeter/data/order-users.csv
```

只测入口，不轮询结果：

```bash
jmeter -n \
  -t jmeter/standard-async-order-load-test.jmx \
  -l /tmp/smart-ticket-jmeter/submit-only.jtl \
  -e -o /tmp/smart-ticket-jmeter/report-submit-only \
  -JTHREADS=100 \
  -JRAMP_SECONDS=60 \
  -JLOOP_COUNT=50 \
  -JPOLL_RESULT=false \
  -JHOST=127.0.0.1 \
  -JPORT=8081 \
  -JCSV_FILE=jmeter/data/order-users.csv
```

## 7. 怎么看报告

打开 HTML 报告：

```bash
open /tmp/smart-ticket-jmeter/report-c50/index.html
```

重点看 `Statistics` 页面。

你要分开看：

```text
POST /api/orders/async
```

这个才是入口提交能力。

```text
异步下单完整链路
```

这个是用户从提交到看到结果的等待时间。

```text
GET /api/order-requests/{requestId}
```

这个是轮询查询压力，不是下单 QPS。

## 8. 阶梯加压顺序

不要一上来就 1000 线程。按这个顺序：

| 轮次 | THREADS | RAMP_SECONDS | LOOP_COUNT |
| --- | ---: | ---: | ---: |
| 1 | 2 | 5 | 3 |
| 2 | 10 | 10 | 10 |
| 3 | 50 | 30 | 20 |
| 4 | 100 | 60 | 50 |
| 5 | 300 | 120 | 50 |
| 6 | 500 | 180 | 100 |

每跑完一轮都看：

- Error% 有没有明显升高
- P99 有没有持续变差
- Kafka 有没有积压
- MySQL 有没有慢 SQL
- Redis 有没有慢命令
- 本机 CPU 是否已经满了

## 9. 什么时候必须停

出现下面情况就停，不要硬压：

- Error% 持续超过 5%
- P99 一直飙升
- Kafka lag 越积越多
- MySQL 连接池耗尽
- MySQL 大量锁等待
- Redis 出现大量慢命令
- 电脑 CPU 长时间 100%

硬压只会得到脏数据，不会得到有效结论。

## 10. 新手最容易看错的地方

第一，总 Throughput 不是下单 QPS。因为轮询会制造很多 GET 请求。

第二，入口成功不等于订单成功。`POST /api/orders/async` 成功只代表请求进入队列。

第三，库存不足时失败是正常业务结果，不是系统崩了。

第四，单机压测上限很低。应用、数据库、Redis、Kafka、JMeter 都在一台电脑上，会互相抢 CPU 和磁盘。

第五，只用一个用户压测不标准。会被用户限流、幂等、一人一单影响。

## 11. 每轮压测记录模板

```text
日期：
分支：
启动 profile：
JMeter 命令：
THREADS：
RAMP_SECONDS：
LOOP_COUNT：
POLL_RESULT：
预计提交量：

POST /api/orders/async Throughput：
POST /api/orders/async Error%：
POST /api/orders/async P95：
POST /api/orders/async P99：
完整链路 P95：
完整链路 P99：
成功订单数：
失败请求数：
Kafka lag：
MySQL 慢 SQL：
Redis 慢命令：
本轮瓶颈：
下一步：
```
