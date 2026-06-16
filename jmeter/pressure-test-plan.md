# smart-ticket-lite JMeter 标准压测方案

## 1. 压测目标

本次压测只针对高并发购票主链路：

```text
获取幂等 token -> POST /api/orders/async -> Kafka 异步创单 -> 轮询 /api/order-requests/{requestId}
```

严禁把旧同步接口 `POST /api/orders` 纳入主链路压测。它已经是兼容入口，不代表高并发能力。

本方案要回答三个问题：

1. 入口能承接多少异步下单请求。
2. Kafka 和消费者能否跟上入口速度。
3. MySQL 最终创单是否成为瓶颈。

## 2. 压测范围

纳入压测：

- `GET /api/orders/idempotency-token`
- `POST /api/orders/async`
- `GET /api/order-requests/{requestId}`
- Kafka 异步创单消费
- Redis 预扣库存
- MySQL 最终扣库存和创建订单

不纳入本轮压测：

- 真实支付
- 退款
- 出票核销
- 后台管理页面
- 旧同步下单接口

## 3. 指标口径

不要只看 JMeter 总吞吐。轮询接口会把 HTTP 请求数放大，导致总 Throughput 看起来很好看，但那不是下单 QPS。

核心指标：

| 指标 | 看哪里 | 合格标准 |
| --- | --- | --- |
| 入口吞吐 | `POST /api/orders/async` Throughput | 阶梯加压中平稳增长 |
| 入口错误率 | `POST /api/orders/async` Error% | 非售罄场景低于 1% |
| 入口延迟 | `POST /api/orders/async` P95/P99 | 不持续恶化 |
| 完整链路体验 | `异步下单完整链路` P95/P99 | 反映用户等待结果时间 |
| 异步创单能力 | 轮询终态成功率、Kafka lag、订单创建数 | 不长期积压 |
| 数据库瓶颈 | 慢 SQL、行锁等待、连接池等待 | 不出现持续锁等待 |

## 4. 环境要求

本机标准环境：

- JDK 17
- Maven
- JMeter 5.6.x
- MySQL
- Redis
- Kafka
- smart-ticket-lite 应用

建议启动应用时使用抢票配置：

```bash
SPRING_PROFILES_ACTIVE=local,flash-sale mvn spring-boot:run
```

默认压测地址：

```text
http://127.0.0.1:8081
```

端口不同就通过 JMeter 参数覆盖。

## 5. 数据准备

压测数据文件：

```text
jmeter/data/order-users.csv
```

字段：

| 字段 | 含义 |
| --- | --- |
| `authToken` | 用户 JWT，不要带 `Bearer` |
| `showId` | 演出 ID |
| `sessionId` | 场次 ID |
| `ticketCategoryId` | 票档 ID |
| `quantity` | 购买数量 |
| `admissionToken` | 等待室 token，等待室关闭时可留空 |
| `idempotencyToken` | 预生成幂等 token，默认留空 |

正式压测必须准备多用户 token。只用一个用户压测会触发用户级限流、幂等、一人一单等逻辑，结论无效。

库存要求：

```text
压测预计提交量 = THREADS * LOOP_COUNT
```

如果要测成功创单能力，库存必须大于预计提交量。  
如果要测售罄保护能力，可以故意把库存设小，但报告中必须标明这是售罄场景。

## 6. 压测场景

### 场景 A：冒烟验证

目的：确认脚本、token、接口、数据能跑通。

参数：

```text
THREADS=2
RAMP_SECONDS=5
LOOP_COUNT=3
POLL_RESULT=true
```

通过标准：

- 能获取幂等 token
- 能提交异步订单
- 能提取 `requestId`
- 轮询能进入 `SUCCESS`、`FAILED`、`CANCELLED`、`COMPENSATED` 之一

### 场景 B：入口基线

目的：测低压下入口基准性能。

参数：

```text
THREADS=50
RAMP_SECONDS=30
LOOP_COUNT=20
POLL_RESULT=true
```

观察：

- `POST /api/orders/async` Throughput
- `POST /api/orders/async` P95/P99
- 错误率
- Kafka 是否积压

### 场景 C：阶梯加压

目的：逐步找到系统拐点。

| 轮次 | THREADS | RAMP_SECONDS | LOOP_COUNT | 预计提交量 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 10 | 10 | 10 | 100 |
| 2 | 50 | 30 | 20 | 1000 |
| 3 | 100 | 60 | 50 | 5000 |
| 4 | 300 | 120 | 50 | 15000 |
| 5 | 500 | 180 | 100 | 50000 |

停止条件：

- 入口错误率持续大于 5%
- P99 持续上升且无法恢复
- Kafka lag 长时间不下降
- MySQL 出现大量锁等待或连接池耗尽
- 本机 CPU 长时间接近 100%，压测机已经成为瓶颈

### 场景 D：只测入口

目的：隔离轮询请求影响，只看入口接收能力。

参数：

```text
POLL_RESULT=false
```

注意：该场景只能证明入口接住了请求，不能证明订单最终创建成功。

### 场景 E：售罄保护

目的：验证低库存下不会超卖。

做法：

- 设置库存小于预计提交量
- 开启轮询
- 压测后检查订单成功数不超过真实库存
- 检查 Redis 和 MySQL 库存一致性

## 7. 风险和限制

1. 单机压测不等于生产压测。应用、JMeter、MySQL、Redis、Kafka 如果都在一台电脑上，CPU 和磁盘会互相抢资源。
2. JMeter 线程数不是越大越好。线程太高时，压测机自己会先变成瓶颈。
3. 轮询会放大 HTTP 请求数。报告必须单独列出入口吞吐和完整链路耗时。
4. 库存不足时，大量失败是业务结果，不代表系统错误。
5. 等待室开启时，CSV 里的 `admissionToken` 必须有效。

## 8. 交付物

每轮压测后必须保留：

- JMeter `.jtl` 原始结果
- HTML 报告目录
- 压测参数
- 应用日志关键片段
- Kafka lag 截图或记录
- MySQL 慢 SQL / 锁等待记录
- Redis 慢日志 / 热点 key 记录
- 结论：瓶颈在哪里，下一步怎么改

## 9. 最终报告模板

```text
压测日期：
代码分支：
启动 profile：
JMeter 版本：
压测机器配置：
应用地址：

场景：
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
成功创单数：
失败请求数：
Kafka 最大 lag：
MySQL 是否有慢 SQL：
Redis 是否有慢命令：

瓶颈判断：
改进建议：
下一轮参数：
```
