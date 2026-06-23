# JMeter 异步下单压测傻瓜式操作文档

本文只针对当前项目里的 JMeter 脚本：

- 测试计划：`scripts/jmeter/async-order-load-test.jmx`
- 一键执行脚本：`scripts/load/run-async-order-jmeter.sh`
- 默认压测用户 CSV：优先使用 `/tmp/async-order-users.csv`，没有该文件才使用 `scripts/jmeter/data/async-order-users.csv`
- 当前压测目标接口：`POST /api/orders/async`

## 1. 先确认压测前置条件

不要跳过这一节。跳过后看到 401、400、500、库存不足，不要先怀疑 JMeter。

### 1.1 应用必须已经启动

IDEA 里启动项目，确认端口是 `8081`。

终端执行：

```bash
curl -sS http://127.0.0.1:8081/actuator/health
```

正常应该看到 `status` 是 `UP`，并且 `db`、`redis` 都是 `UP`。

### 1.2 Kafka 必须可用

终端执行：

```bash
kafka-topics --bootstrap-server 127.0.0.1:9092 --describe --topic smart-ticket.async-order.create
```

正常重点看：

```text
PartitionCount: 64
```

### 1.3 压测 CSV 必须是 50 个用户

终端执行：

```bash
wc -l /tmp/async-order-users.csv
head -3 /tmp/async-order-users.csv
```

正常应该是：

```text
51 /tmp/async-order-users.csv
```

第一行表头必须是：

```text
authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken
```

每一行必须有 6 列。第 6 列 `admissionToken` 不能为空。现在开启了等待室，没有 admissionToken 会直接失败：

```text
缺少等待室入场资格
```

### 1.4 每轮压测前刷新 admissionToken

`admissionToken` 是一次性的。只要跑过一轮，CSV 里的 admissionToken 就可能已经被消费。下一轮继续用旧 CSV，常见错误就是：

```text
等待室入场资格无效或已使用
```

如果你已经重启过应用，等待室接口鉴权配置已经生效，可以走正式等待室流程发放 token。当前最稳的本地压测方式，是直接按项目 Redis key 规则给 `/tmp/async-order-users.csv` 重刷一批 admissionToken：

```bash
cd /Users/zewbao/Desktop/workspace/smart-ticket-lite

node <<'NODE'
const fs = require('fs');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const dataFile = '/tmp/async-order-users.csv';
const lines = fs.readFileSync(dataFile, 'utf8').trim().split(/\r?\n/);
const out = ['authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken'];

for (const line of lines.slice(1)) {
  const [authToken, showId, sessionId, ticketCategoryId, quantity] = line.split(',');
  const payload = JSON.parse(Buffer.from(authToken.split('.')[1], 'base64').toString('utf8'));
  const token = `admit_${crypto.randomBytes(16).toString('hex')}`;
  const key = `waiting-room:admission:ticket:${ticketCategoryId}:user:${payload.userId}:token:${token}`;
  execFileSync('redis-cli', ['SET', key, '1', 'EX', '3600'], { encoding: 'utf8' });
  out.push([authToken, showId, sessionId, ticketCategoryId, quantity, token].join(','));
}

fs.writeFileSync(dataFile, out.join('\n') + '\n');
console.log(`refreshed ${out.length - 1} admission tokens`);
NODE
```

执行后检查：

```bash
wc -l /tmp/async-order-users.csv
awk -F, 'NR==2 {print "columns=" NF ", admissionTokenPrefix=" substr($6,1,6)}' /tmp/async-order-users.csv
```

正常应该看到：

```text
51 /tmp/async-order-users.csv
columns=6, admissionTokenPrefix=admit_
```

### 1.5 演出和库存必须可见

终端执行：

```bash
curl -sS http://127.0.0.1:8081/api/shows
curl -sS http://127.0.0.1:8081/api/sessions/1/ticket-categories
```

正常应该能看到 `showId=1`、`sessionId=1`、`ticketCategoryId=1/2/3`。

当前 CSV 默认压的是：

```text
showId=1
sessionId=1
ticketCategoryId=2
quantity=1
```

### 1.6 压测前必须预热 Redis 库存

这一步不能省。MySQL 里有库存，只代表数据库有票；压测入口扣的是 Redis 里的库存，开启 flash-sale profile 后还会优先扣 Redis bucket 库存。如果没有预热，JMeter 一启动就可能看到库存不足、Redis 库存为空、bucket 未初始化这类错误。

先获取后台 token：

```bash
read -s SMART_TICKET_ADMIN_PASSWORD
export SMART_TICKET_ADMIN_PASSWORD

ADMIN_TOKEN=$(curl -sS -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000001\",\"password\":\"$SMART_TICKET_ADMIN_PASSWORD\"}" | /usr/bin/jq -r '.data.token')

echo "$ADMIN_TOKEN"
```

如果 `echo "$ADMIN_TOKEN"` 打印出 `null` 或空字符串，先停下来，不要压测。说明管理员账号、密码或本地数据还没准备好。

当前 CSV 默认压 `ticketCategoryId=2`，所以先只预热 2 号票档：

```bash
curl -sS -X POST http://127.0.0.1:8081/api/admin/ticket-categories/2/stock/preheat \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

如果你想把所有票档都预热：

```bash
curl -sS -X POST http://127.0.0.1:8081/api/admin/stocks/preheat-all \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

预热后立刻检查库存视图：

```bash
curl -sS http://127.0.0.1:8081/api/admin/stocks \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

重点确认压测票档，也就是 `ticketCategoryId=2`：

```text
redisAvailableStock > 0
redisExpectedConsistent=true
mysqlStockConsistent=true
diff=0
```

如果 `redisAvailableStock` 是 `0`，先不要点 JMeter 启动。继续查 MySQL 里 2 号票档是否真的有可售库存：

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -p -D smart_ticket_lite \
  -e "SELECT * FROM ticket_stock WHERE ticket_category_id = 2; SELECT * FROM ticket_stock_bucket WHERE ticket_category_id = 2 ORDER BY bucket_no;"
```

## 2. JMeter 图形界面压测

### 2.1 打开 JMeter

macOS 终端执行：

```bash
jmeter
```

如果提示找不到命令，先安装：

```bash
brew install jmeter
```

### 2.2 打开测试计划

JMeter 菜单里点：

```text
File -> Open
```

选择：

```text
/Users/zewbao/Desktop/workspace/smart-ticket-lite/scripts/jmeter/async-order-load-test.jmx
```

打开后左侧应该能看到：

```text
Smart Ticket 异步下单压测
  可选预热：订单提交元数据
  异步下单压测线程组
    用户与票档 CSV
    目标入口吞吐控制
    01 获取下单幂等 Token
    02 提交异步下单请求
    03 轮询 Kafka 创单结果
```

### 2.3 设置用户变量

点左侧最顶层：

```text
Smart Ticket 异步下单压测
```

在右侧 `用户定义的变量` 里检查这些值：

```text
base_url       http://127.0.0.1:8081
data_file      /tmp/async-order-users.csv
risk_decision  pass
poll_result    true
```

如果 `data_file` 还是 `scripts/jmeter/data/async-order-users.csv`，手动改成：

```text
/tmp/async-order-users.csv
```

原因很简单：仓库里的 CSV 是模板，真正 50 个用户在 `/tmp/async-order-users.csv`。

### 2.4 设置并发线程数

点左侧：

```text
异步下单压测线程组
```

先用小流量跑通，不要一上来打满。

第一轮建议：

```text
Number of Threads(users): 10
Ramp-up period(seconds): 10
Duration(seconds): 60
```

含义：

```text
10 个并发用户
10 秒内逐步启动完
持续压 60 秒
```

### 2.5 设置目标 QPS

点左侧：

```text
目标入口吞吐控制
```

这里用的是 JMeter 的 `Constant Throughput Timer`，单位是“每分钟请求数”，不是每秒。

如果你要 20 QPS，填：

```text
1200
```

换算公式：

```text
每分钟请求数 = 目标 QPS * 60
```

常用值：

```text
10 QPS  -> 600
20 QPS  -> 1200
50 QPS  -> 3000
100 QPS -> 6000
200 QPS -> 12000
```

第一轮建议填：

```text
1200
```

### 2.6 确认 CSV 配置

点左侧：

```text
用户与票档 CSV
```

确认：

```text
Filename: ${data_file}
Variable Names: authToken,showId,sessionId,ticketCategoryId,quantity,admissionToken
Ignore first line: true
Recycle on EOF: true
Stop thread on EOF: false
Sharing mode: All threads
```

不要乱改列名。JMX 里下单请求体会用这些变量：

```json
{
  "showId": ${showId},
  "sessionId": ${sessionId},
  "ticketCategoryId": ${ticketCategoryId},
  "quantity": ${quantity},
  "idempotencyToken": "${idempotencyToken}",
  "admissionToken": "${admissionToken}"
}
```

### 2.7 添加查看结果树，只用于跑通

第一次跑通可以加 `View Results Tree`。

操作：

```text
右键 异步下单压测线程组
Add -> Listener -> View Results Tree
```

只用于 10 并发以内排错。正式压测要禁用或删除它，否则 JMeter 自己会拖慢压测。

### 2.8 点击启动

点击前最后确认三件事：

```text
/tmp/async-order-users.csv 有 50 个用户和 admissionToken
ticketCategoryId=2 已经完成 Redis 库存预热
Kafka topic smart-ticket.async-order.create 存在并且应用已连接
```

点顶部绿色三角按钮。

第一次小流量跑通时，重点看三类请求：

```text
01 获取下单幂等 Token
02 提交异步下单请求
03 查询异步请求结果
```

正常情况：

```text
01 返回 code=0
02 返回 code=0，状态一般先是 QUEUED
03 轮询后状态变成 SUCCESS
```

如果 `03` 偶尔还没查到 SUCCESS，不一定是失败，可能是 Kafka 消费还没处理完。可以把 `poll_max_attempts` 调大，或者把 `poll_interval_ms` 调大。

## 3. 命令行一键压测

图形界面适合你看懂流程。正式压测建议用命令行，结果更干净。

进入项目目录：

```bash
cd /Users/zewbao/Desktop/workspace/smart-ticket-lite
```

第一轮跑通：

```bash
THREADS=10 \
RAMP_SECONDS=10 \
DURATION_SECONDS=60 \
TARGET_QPS=20 \
./scripts/load/run-async-order-jmeter.sh
```

脚本会自动使用 `/tmp/async-order-users.csv`。

跑完会打印：

```text
原始结果：reports/jmeter/某次运行/result.jtl
JMeter 日志：reports/jmeter/某次运行/jmeter.log
HTML 报告：reports/jmeter/某次运行/html/index.html
```

打开 HTML 报告：

```bash
LATEST_RUN=$(ls -t reports/jmeter | head -1)
open "reports/jmeter/$LATEST_RUN/html/index.html"
```

如果你不知道最新目录是哪一个：

```bash
ls -lt reports/jmeter | head
```

## 4. 推荐压测节奏

不要直接打大流量。按下面节奏来。

### 4.1 跑通验证

```bash
THREADS=10 \
RAMP_SECONDS=10 \
DURATION_SECONDS=60 \
TARGET_QPS=20 \
./scripts/load/run-async-order-jmeter.sh
```

目标：

```text
错误率接近 0
能产生 SUCCESS 的异步订单请求
Kafka lag 不持续增长
```

### 4.2 小压力

```bash
THREADS=30 \
RAMP_SECONDS=30 \
DURATION_SECONDS=180 \
TARGET_QPS=50 \
./scripts/load/run-async-order-jmeter.sh
```

### 4.3 中压力

```bash
THREADS=80 \
RAMP_SECONDS=60 \
DURATION_SECONDS=300 \
TARGET_QPS=100 \
./scripts/load/run-async-order-jmeter.sh
```

### 4.4 更高压力

```bash
THREADS=150 \
RAMP_SECONDS=90 \
DURATION_SECONDS=300 \
TARGET_QPS=200 \
./scripts/load/run-async-order-jmeter.sh
```

如果 200 QPS 都稳定，再继续往上加。不要在本机环境里把结果当生产容量，本机 MySQL、Redis、Kafka、应用都挤在一起，瓶颈会互相污染。

## 5. 压测时要看什么

### 5.1 JMeter 指标

HTML 报告重点看：

```text
Error %
Throughput
Response Times Percentiles
95th pct
99th pct
```

接口层判断：

```text
01 获取下单幂等 Token 慢：Redis 或鉴权压力
02 提交异步下单慢：入口限流、等待室、Redis 预扣、Kafka producer
03 查询异步请求结果慢：Kafka consumer、MySQL 创单、结果缓存
```

### 5.2 Kafka lag

压测中另开一个终端：

```bash
kafka-consumer-groups \
  --bootstrap-server 127.0.0.1:9092 \
  --describe \
  --group smart-ticket-async-order-create
```

看 `LAG`。

判断：

```text
LAG 偶尔上升又回落：正常
LAG 持续上升：消费者处理不过来
LAG 为 0 但 JMeter 失败：问题多半在入口、等待室、限流、Redis、JWT、CSV
```

### 5.3 数据库订单请求状态

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -p -D smart_ticket_lite
```

进入 MySQL 后执行：

```sql
SELECT status, COUNT(*)
FROM ticket_order_request
GROUP BY status;

SELECT status, COUNT(*)
FROM ticket_order
GROUP BY status;
```

正常会看到：

```text
ticket_order_request: SUCCESS 较多
ticket_order: PENDING_PAYMENT 较多
```

如果大量 `FAILED`，继续查：

```sql
SELECT fail_reason, COUNT(*)
FROM ticket_order_request
WHERE status = 'FAILED'
GROUP BY fail_reason
ORDER BY COUNT(*) DESC;
```

### 5.4 库存一致性

获取管理员 token 后看库存：

```bash
read -s SMART_TICKET_ADMIN_PASSWORD
export SMART_TICKET_ADMIN_PASSWORD

ADMIN_TOKEN=$(curl -sS -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000001\",\"password\":\"$SMART_TICKET_ADMIN_PASSWORD\"}" | /usr/bin/jq -r '.data.token')

curl -sS http://127.0.0.1:8081/api/admin/stocks \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

重点看：

```text
mysqlStockConsistent=true
redisExpectedConsistent=true
diff=0
```

## 6. 常见错误和处理

### 6.1 `token已过期`

原因：`/tmp/async-order-users.csv` 里的 JWT 过期。

处理：重新生成或刷新 50 个用户 token。不要继续用旧 CSV 压。

### 6.2 `缺少等待室入场资格`

原因：CSV 第 6 列 `admissionToken` 为空，或者 token 已过期。

检查：

```bash
awk -F, 'NR==2 {print NF, $6}' /tmp/async-order-users.csv
```

正常：

```text
6 admit_xxx
```

### 6.3 `等待室入场资格无效或已使用`

原因：admissionToken 是一次性的，同一个用户同一行重复提交后会被消费。

处理：重新发放 admissionToken，再跑。

### 6.4 `幂等 token 无效或已过期`

原因：`01 获取下单幂等 Token` 失败，或者提取 JSON 失败。

处理：

```text
看 01 请求是否 code=0
看 JSON Extractor 是否提取 $.data.token
```

### 6.5 `异步提交失败`

先看 `02 提交异步下单请求` 的响应体。

常见原因：

```text
限流触发
等待室 token 不对
库存未预热
票档不可售
JWT 过期
```

### 6.6 `/api/shows` 是空

原因：演出或场次不是 `PUBLISHED`。

检查：

```sql
SELECT id, status FROM show_info;
SELECT id, status FROM performance_session;
SELECT id, status FROM ticket_category;
```

### 6.7 Kafka lag 持续增长

说明入口进 Kafka 的速度超过消费者创单速度。

处理方向：

```text
降低 TARGET_QPS
提高消费者并发
检查 MySQL 慢 SQL
检查连接池是否打满
检查 ticket_order_request / ticket_order 索引
```

### 6.8 JMeter 自己卡

正式压测时禁用这些 Listener：

```text
View Results Tree
View Results in Table
```

只保留汇总报告或命令行 HTML 报告。

## 7. 压测前清理建议

如果只是跑通，不一定要清库。

如果要做一轮干净压测，先记录当前数量：

```sql
SELECT COUNT(*) FROM ticket_order_request;
SELECT COUNT(*) FROM ticket_order;
SELECT ticket_category_id, available_stock, locked_stock, sold_stock FROM ticket_stock;
SELECT ticket_category_id, SUM(available_stock), SUM(locked_stock), SUM(sold_stock)
FROM ticket_stock_bucket
GROUP BY ticket_category_id;
```

不要手动乱删订单和库存。订单、请求、Redis 预扣、bucket 库存是关联的，乱删会让一致性判断失真。

## 8. 当前你可以直接执行的最小命令

确认应用、MySQL、Redis、Kafka 都开着，然后执行：

```bash
cd /Users/zewbao/Desktop/workspace/smart-ticket-lite

THREADS=10 \
RAMP_SECONDS=10 \
DURATION_SECONDS=60 \
TARGET_QPS=20 \
./scripts/load/run-async-order-jmeter.sh
```

跑完打开报告：

```bash
ls -lt reports/jmeter | head
LATEST_RUN=$(ls -t reports/jmeter | head -1)
open "reports/jmeter/$LATEST_RUN/html/index.html"
```

`LATEST_RUN` 会自动取最新一次报告目录。
