# Smart Ticket 正式 JMeter 压测计划

本文用于当前项目正式压测异步下单链路：

- 测试计划：`scripts/jmeter/async-order-load-test.jmx`
- 数据准备：`scripts/load/prepare-async-order-jmeter-data.sh`
- 命令行压测：`scripts/load/run-async-order-jmeter.sh`
- 正式 CSV：`/tmp/async-order-users-formal.csv`

## 1. 正式压测前检查

确认应用健康：

```bash
curl -sS http://127.0.0.1:8081/actuator/health
```

确认 Kafka topic：

```bash
kafka-topics --bootstrap-server 127.0.0.1:9092 \
  --describe \
  --topic smart-ticket.async-order.create
```

确认库存已预热：

```bash
read -s SMART_TICKET_ADMIN_PASSWORD
export SMART_TICKET_ADMIN_PASSWORD

ADMIN_TOKEN=$(curl -sS -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000001\",\"password\":\"$SMART_TICKET_ADMIN_PASSWORD\"}" | /usr/bin/jq -r '.data.token')

curl -sS -X POST http://127.0.0.1:8081/api/admin/ticket-categories/2/stock/preheat \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

正常重点看：

```text
redisAvailableStock > 0
redisExpectedConsistent = true
mysqlStockConsistent = true
diff = 0
```

## 2. 生成正式压测 CSV

正式压测不能循环复用 50 个 admissionToken。等待室 token 是一次性的，用过一次就失效。

先生成足够多的请求行。比如第一轮 10 QPS、60 秒，理论提交量约 600，建议生成 1000 行：

```bash
ROWS=1000 ./scripts/load/prepare-async-order-jmeter-data.sh
```

中压 100 QPS、300 秒，理论提交量约 30000，建议生成 40000 行：

```bash
ROWS=40000 ./scripts/load/prepare-async-order-jmeter-data.sh
```

生成后检查：

```bash
wc -l /tmp/async-order-users-formal.csv
head -2 /tmp/async-order-users-formal.csv
```

## 3. 推荐压测阶梯

第一轮正式跑通。你当前 2 号票档可售库存约 1000，先不要超过 600 单：

```bash
ROWS=1000 ./scripts/load/prepare-async-order-jmeter-data.sh

THREADS=10 \
RAMP_SECONDS=10 \
DURATION_SECONDS=60 \
TARGET_QPS=10 \
./scripts/load/run-async-order-jmeter.sh
```

第二轮小压力：

```bash
ROWS=10000 ./scripts/load/prepare-async-order-jmeter-data.sh

THREADS=30 \
RAMP_SECONDS=30 \
DURATION_SECONDS=120 \
TARGET_QPS=50 \
./scripts/load/run-async-order-jmeter.sh
```

第三轮中压力：

```bash
ROWS=40000 ./scripts/load/prepare-async-order-jmeter-data.sh

THREADS=80 \
RAMP_SECONDS=60 \
DURATION_SECONDS=300 \
TARGET_QPS=100 \
./scripts/load/run-async-order-jmeter.sh
```

第四轮高一点：

```bash
ROWS=80000 ./scripts/load/prepare-async-order-jmeter-data.sh

THREADS=150 \
RAMP_SECONDS=90 \
DURATION_SECONDS=300 \
TARGET_QPS=200 \
./scripts/load/run-async-order-jmeter.sh
```

本机环境不要盲目追高。应用、MySQL、Redis、Kafka、JMeter 都在一台机器上时，瓶颈会互相污染。

如果要跑 50 QPS 以上，先确认库存足够。请求量估算：

```text
理论提交量 = TARGET_QPS * DURATION_SECONDS
准备 ROWS >= 理论提交量 * 1.2
可售库存 >= 理论提交量
```

当前 `ticketCategoryId=2` 初始库存是 1000，所以 10 QPS * 60 秒最稳。要跑 50 QPS * 120 秒这种 6000 单级别测试，需要先通过后台库存调整补足库存。

## 4. 当前电脑压测基线

当前机器配置：

```text
MacBook Air
Apple M4
10 核 CPU：4 性能核 + 6 能效核
16GB 内存
JMeter 5.6.3
Kafka topic smart-ticket.async-order.create：64 分区
JMeter、Spring Boot、MySQL、Redis、Kafka 全部同机运行
```

这台机器适合做：

```text
单机功能跑通
本机稳定吞吐对比
本机短时间洪峰演练
入口削峰和 Kafka lag 回落观察
```

不适合直接证明：

```text
生产真实几十万公网请求承载能力
大麦级全链路容量
多机 Kafka / Redis / MySQL 集群能力
```

原因是：JMeter 和被测系统同机时，CPU、内存、端口、磁盘 IO、Kafka、Redis、MySQL 都在抢同一台 MacBook Air 的资源。高压下你看到的瓶颈，可能是压测机瓶颈，而不是业务代码瓶颈。

脚本已经按这台机器做了保护：

```text
JMeter HEAP 默认：-Xms512m -Xmx2g
CSV 行数不足时直接拒绝启动
洪峰脚本默认使用本机保守档
```

## 5. 洪峰压测：模拟瞬时大量请求打进来

洪峰压测和稳定吞吐压测不是一个目标。

稳定吞吐压测看：

```text
系统在固定 QPS 下能不能稳定低错误运行
```

洪峰压测看：

```text
瞬间大量请求进入时，入口是否快速削峰
Kafka 是否承接住消息堆积
消费者是否能在洪峰后逐步追平
最终订单、请求、库存是否一致
```

本机无法真实模拟“大麦级几十万请求同时从公网打进来”。你的 Mac 上 JMeter、应用、MySQL、Redis、Kafka 都在同一台机器，几十万请求会先把本机压测机、文件句柄、端口、CPU 或 Kafka 本机进程打满。

本机可以做的是：

```text
短时间高并发洪峰尝试
验证入口削峰逻辑
验证 Kafka lag 是否先升后降
验证最终创单结果是否完整
```

### 5.1 洪峰压测前准备

本机第一轮洪峰演练，建议先打 4 万库存、5 万 CSV，避免库存和 token 先成为瓶颈：

```bash
CONFIRM_RESET=YES RESET_STOCK_QUANTITY=40000 ./scripts/load/reset-load-test-env.sh
ROWS=50000 ./scripts/load/prepare-async-order-jmeter-data.sh
```

如果想尝试 10 万请求：

```bash
CONFIRM_RESET=YES RESET_STOCK_QUANTITY=120000 ./scripts/load/reset-load-test-env.sh
ROWS=120000 ./scripts/load/prepare-async-order-jmeter-data.sh
```

注意：

```text
ROWS >= 目标请求数
库存 >= 目标成功创单数
admissionToken 每行一个，不能复用
```

### 5.2 本机洪峰档位

洪峰脚本：

```bash
./scripts/load/run-burst-order-jmeter.sh
```

支持 4 个档位：

```text
smoke   300 QPS，15 秒，50 线程，用于确认脚本没问题
local   2000 QPS，20 秒，200 线程，适合当前 M4 Air 的第一轮洪峰
strong  5000 QPS，20 秒，400 线程，本机强压
extreme 10000 QPS，15 秒，800 线程，本机极限尝试
```

第一轮建议：

```bash
BURST_LEVEL=smoke ./scripts/load/run-burst-order-jmeter.sh
```

确认没有脚本错误后，再跑：

```bash
BURST_LEVEL=local ./scripts/load/run-burst-order-jmeter.sh
```

如果 `local` 档可以稳定完成，Kafka lag 能回落，再尝试：

```bash
BURST_LEVEL=strong ./scripts/load/run-burst-order-jmeter.sh
```

极限档：

```bash
BURST_LEVEL=extreme ./scripts/load/run-burst-order-jmeter.sh
```

极限档失败不一定是系统不行，也可能是 MacBook Air 同机压测先到瓶颈。

这里 `POLL_RESULT=false` 是故意的。洪峰测试重点是“提交入口瞬时涌入”，如果每个请求后面再轮询结果，会把查询接口压力混进来，结论会乱。

跑完后再看异步创单是否追平。

如果你要手动覆盖参数：

```bash
THREADS=300 \
RAMP_SECONDS=1 \
DURATION_SECONDS=20 \
TARGET_QPS=3000 \
POLL_RESULT=false \
./scripts/load/run-burst-order-jmeter.sh
```

估算请求量：

```text
预计请求数 = TARGET_QPS * DURATION_SECONDS
```

脚本会检查 CSV 行数，不够会拒绝启动。

### 5.3 本机观察指标

压测时另开终端看本机资源：

```bash
top -o cpu
```

重点看：

```text
java 进程 CPU 是否长期打满
mysqld CPU 是否打满
redis-server CPU 是否打满
内存压力是否变黄/变红
```

如果 JMeter 所在 java 进程先打满 CPU，这轮结果主要说明压测机不够，不代表服务端真实上限。

### 5.4 洪峰压测期间看 Kafka lag

另开终端持续执行：

```bash
watch -n 1 "kafka-consumer-groups --bootstrap-server 127.0.0.1:9092 --describe --group smart-ticket-async-order-create"
```

如果没有 `watch`，用：

```bash
while true; do
  date
  kafka-consumer-groups \
    --bootstrap-server 127.0.0.1:9092 \
    --describe \
    --group smart-ticket-async-order-create
  sleep 1
done
```

判断：

```text
洪峰时 LAG 快速升高：正常，说明 Kafka 在承接削峰
停止压测后 LAG 持续下降直到 0：好，消费者能追平
LAG 一直涨不回落：消费者或 MySQL 创单能力不足
LAG 为 0 但入口大量失败：入口限流、等待室、库存、JWT、CSV 或 JMeter 本身有问题
```

### 5.5 洪峰压测后看完整链路

```sql
SELECT status, fail_reason, COUNT(*)
FROM ticket_order_request
GROUP BY status, fail_reason
ORDER BY COUNT(*) DESC;

SELECT status, COUNT(*)
FROM ticket_order
GROUP BY status;

SELECT *
FROM ticket_stock
WHERE ticket_category_id = 2;

SELECT bucket_version,
       COUNT(*) bucket_count,
       SUM(total_stock) total_stock,
       SUM(available_stock) available_stock,
       SUM(locked_stock) locked_stock,
       SUM(sold_stock) sold_stock
FROM ticket_stock_bucket
WHERE ticket_category_id = 2
GROUP BY bucket_version;
```

合格现象：

```text
应用没有崩
JMeter 错误主要是可解释的限流/等待室/库存拒绝，而不是 500 或连接失败
ticket_order_request 最终没有大量 QUEUED / PROCESSING 卡住
Kafka lag 最终回到 0
库存总和一致
```

如果你要真正模拟几十万瞬时请求，需要分布式压测：

```text
多台压测机运行 JMeter agent
应用、Kafka、Redis、MySQL 不和 JMeter 在同一台机器
Kafka topic 分区数和消费者并发提前配置好
MySQL 连接池、max_connections、磁盘 IO 提前调大
系统 ulimit、端口范围、JVM 堆内存提前调好
```

## 6. 看结果

命令行脚本跑完会输出：

```text
原始结果：reports/jmeter/某次运行/result.jtl
JMeter 日志：reports/jmeter/某次运行/jmeter.log
HTML 报告：reports/jmeter/某次运行/html/index.html
```

打开最新 HTML 报告：

```bash
LATEST_RUN=$(ls -t reports/jmeter | head -1)
open "reports/jmeter/$LATEST_RUN/html/index.html"
```

快速统计 JTL：

```bash
LATEST_RUN=$(ls -t reports/jmeter | head -1)
awk -F, 'NR>1 {total++; label[$3]++; if($8=="true") ok[$3]++; else bad[$3]++}
END {for (l in label) print l, "total="label[l], "ok="ok[l]+0, "bad="bad[l]+0}' \
"reports/jmeter/$LATEST_RUN/result.jtl"
```

## 7. 压测中盯 Kafka

另开终端：

```bash
kafka-consumer-groups \
  --bootstrap-server 127.0.0.1:9092 \
  --describe \
  --group smart-ticket-async-order-create
```

判断：

```text
LAG 偶尔上升又回落：正常
LAG 持续上升：消费者处理不过来
LAG 为 0 但 JMeter 失败：多半是入口、等待室、限流、库存、JWT 或 CSV
```

## 8. 压测后查数据库

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -p -D smart_ticket_lite
```

```sql
SELECT status, fail_reason, COUNT(*)
FROM ticket_order_request
GROUP BY status, fail_reason
ORDER BY COUNT(*) DESC;

SELECT status, COUNT(*)
FROM ticket_order
GROUP BY status;
```

正式压测报告里至少记录：

```text
线程数
Ramp-up
持续时间
目标 QPS
JMeter Throughput
Error %
P95 / P99
Kafka 最大 lag
ticket_order_request SUCCESS / FAILED 数量
ticket_order 创建数量
库存一致性 diff
```
