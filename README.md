# SmartTicket Lite

基于 Java 17、Spring Boot 3.x、MyBatis-Plus 与 MySQL 的单体票务系统，用于练习演出查询、订单状态和库存流转。

## 已实现能力

第一阶段：

- 用户、演出、场次、票档查询
- 创建订单、查询订单、取消订单
- 统一响应、异常处理与参数校验

第二阶段：

- Redis 缓存演出详情、场次与票档查询
- Redis `SET NX` 防止请求处理中重复下单
- 订单状态机：`PENDING_PAYMENT`、`PAID`、`CANCELLED`、`CLOSED`
- 模拟支付与主动取消
- RabbitMQ TTL + 死信队列自动关闭超时订单
- 定时任务兜底关闭过期待支付订单

第三阶段：

- `POST /api/orders/async` 异步提交下单请求
- 快速返回 `requestId`，不在接口线程中创建正式订单
- `ticket_order_request` 记录 `PROCESSING` / `SUCCESS` / `FAILED`
- RabbitMQ 消费者异步扣库存并创建订单
- `GET /api/order-requests/{requestId}` 查询异步下单结果
- 异步订单创建成功后继续进入 `PENDING_PAYMENT` 状态，沿用支付、取消、超时关闭流程

第四阶段：

- Actuator 健康检查与基础指标
- 接口耗时日志和慢接口 warn 日志
- Redis 固定窗口限流：IP 级、接口级、用户级、票档级
- 下单幂等 Token：同步下单和异步下单均需携带一次性 token
- 库存 `version` 字段维护，辅助并发排查和后续乐观锁升级
- 数据库索引优化 SQL 与 EXPLAIN 慢 SQL 分析
- JMeter 异步下单压测方案、压测前后 SQL 和结果模板

第五阶段：

- Redis 库存预热与 Redis Lua 原子预扣库存
- 异步下单接入 Redis 预扣，入口快速失败库存不足请求
- 本地消息表 `local_message`，保存待发送 MQ 消息
- 后台定时任务扫描本地消息表并发送 RabbitMQ
- RabbitMQ Publisher Confirm 基础配置
- 异步下单消费者 DLQ 兜底处理
- Redis / MySQL 库存一致性检查
- 第五阶段压测计划、JMeter 脚本、压测前后 SQL 和验收材料

## 技术栈

Java 17、Spring Boot 3.x、Spring MVC、MyBatis-Plus、MySQL 8.x、Redis、RabbitMQ、Maven、Lombok。

## 本地启动

环境要求：JDK 17、MySQL 8.x、Redis、RabbitMQ、Maven。

1. 启动 MySQL，并准备 `smart_ticket_lite` 数据库。仓库当前主要提供阶段改造 SQL 和检查 SQL，执行前先确认本地已有基础业务表。
2. 启动 Redis：

```bash
redis-server
```

3. 启动 RabbitMQ，并开启管理台：

```bash
rabbitmq-server
rabbitmq-plugins enable rabbitmq_management
```

管理台地址：[http://localhost:15672](http://localhost:15672)，本地默认账号通常为 `guest / guest`。

4. 检查本地连接配置：[application-local.yml](/Users/zewbao/Desktop/smart-ticket-lite/src/main/resources/application-local.yml)。
5. 启动项目：

```bash
mvn spring-boot:run
```

服务默认地址：`http://localhost:8081`。

## HTTP 测试

第二阶段同步订单流程：在 IDEA 打开 [phase2-full-flow.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-full-flow.http)，从上到下依次点击请求左侧绿色运行按钮。

第三阶段异步下单流程：在 IDEA 打开 [phase3-async-order-full-flow.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase3-async-order-full-flow.http)，先提交异步下单，复制返回的 `requestId` 查询结果；当结果为 `SUCCESS` 后，再复制返回的 `orderId` 继续支付、取消或等待超时关闭。

接口文档见 [phase2-api.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-api.md)。

第三阶段 RabbitMQ 检查见 [phase3-rabbitmq-check.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/mq/phase3-rabbitmq-check.md)。

压测说明见 [phase3-async-order-plan.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase3-async-order-plan.md)。

第四阶段 JMeter 压测入口：

- 测试计划：[phase4-async-order-test.jmx](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/jmeter/phase4-async-order-test.jmx)
- 压测说明：[phase4-jmeter-test-plan.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-jmeter-test-plan.md)
- 压测前 SQL：[phase4-jmeter-before.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-jmeter-before.sql)
- 压测后 SQL：[phase4-jmeter-after.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-jmeter-after.sql)

第四阶段验收入口：

- 观测能力：[phase4-observability.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-observability.md)
- 限流设计：[phase4-rate-limit-design.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-rate-limit-design.md)
- 幂等测试：[phase4-idempotency-token-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase4-idempotency-token-api.http)
- 库存 version：[phase4-stock-optimistic-lock.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-stock-optimistic-lock.md)
- 索引优化：[phase4-index-optimization.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-index-optimization.sql)
- EXPLAIN 分析：[phase4-explain-sql.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-explain-sql.sql)
- 慢 SQL 文档：[phase4-slow-sql-analysis.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-slow-sql-analysis.md)
- 总验收清单：[phase4-acceptance-checklist.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-acceptance-checklist.md)

第五阶段 Redis 库存入口：

- Redis 库存设计：[phase5-redis-stock-design.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-redis-stock-design.md)
- Redis 库存 HTTP 测试：[phase5-redis-stock-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase5-redis-stock-api.http)
- Redis/MySQL 库存检查 SQL：[phase5-stock-consistency-check.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase5-stock-consistency-check.sql)

第五阶段可靠消息与压测入口：

- 本地消息表 SQL：[phase5-local-message.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase5-local-message.sql)
- 可靠消息 HTTP 测试：[phase5-reliable-message-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase5-reliable-message-api.http)
- 可靠消息设计：[phase5-reliable-message-design.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-reliable-message-design.md)
- 可靠消息流程说明：[phase5-reliable-message-flow-summary.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-reliable-message-flow-summary.md)
- 压测计划：[phase5-performance-test-plan.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-performance-test-plan.md)
- JMeter 脚本：[phase5-reliable-async-order-test.jmx](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/jmeter/phase5-reliable-async-order-test.jmx)
- 压测前 SQL：[phase5-before-test.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase5-before-test.sql)
- 压测后 SQL：[phase5-after-test.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase5-after-test.sql)
- 压测结果模板：[phase5-performance-result-template.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-performance-result-template.md)
- 压测分析指南：[phase5-performance-analysis-guide.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-performance-analysis-guide.md)
- 第五阶段验收清单：[phase5-acceptance-checklist.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-acceptance-checklist.md)

本地测试 admin 接口：

```text
POST /api/admin/stocks/preload
POST /api/admin/stocks/{ticketCategoryId}/preload
GET  /api/admin/stocks/{ticketCategoryId}/redis
GET  /api/admin/stocks/{ticketCategoryId}/consistency
```

这些接口当前没有权限控制，仅用于本地联调测试，生产环境不能直接暴露。

## 核心流程

```text
选择演出与票档 -> 创建订单并锁库存 -> 支付 / 取消 / 超时关闭
```

异步下单链路：

```text
POST /api/orders/async
-> Redis Lua 预扣库存
-> 同事务保存 ticket_order_request(PROCESSING) 和 local_message(PENDING)
-> 返回 requestId
-> LocalMessagePublishTask 定时发送 order.async.create 消息
-> local_message 标记 SENT
-> AsyncCreateOrderConsumer 消费消息
-> 条件扣库存
-> 创建 ticket_order(PENDING_PAYMENT)
-> ticket_order_request 标记 SUCCESS 并写入 orderId
-> 用户通过 GET /api/order-requests/{requestId} 查询结果
```

| 状态 | 含义 |
|---|---|
| `PENDING_PAYMENT` | 已创建，等待支付 |
| `PAID` | 已支付 |
| `CANCELLED` | 用户主动取消 |
| `CLOSED` | 超时关闭 |

| 操作 | available_stock | locked_stock | sold_stock |
|---|---:|---:|---:|
| 创建订单 | 减少 | 增加 | 不变 |
| 支付订单 | 不变 | 减少 | 增加 |
| 取消/超时关闭 | 增加 | 减少 | 不变 |

异步请求状态：

| 状态 | 含义 |
|---|---|
| `PROCESSING` | 请求已提交，等待消费者处理 |
| `SUCCESS` | 下单成功，`orderId` 有值 |
| `FAILED` | 下单失败，查看 `failReason` |

RabbitMQ 在第三阶段的作用：

- `order.async.queue`：削峰异步创建订单。
- `smart-ticket.order.timeout.delay.queue`：订单创建后延迟触发超时关闭检查。
- `smart-ticket.order.timeout.dead.queue`：TTL 到期后的真正消费队列。

RabbitMQ 在第五阶段的作用：

- `local_message`：发送前先落库，避免请求成功但 MQ 消息无法追踪。
- `LocalMessagePublishTask`：每 3 秒扫描待发送消息并投递 RabbitMQ。
- `order.async.dlq`：异步下单消费失败兜底队列。

第五阶段运行顺序：

```text
执行 phase5-local-message.sql
-> 执行 phase5-before-test.sql
-> 启动 MySQL / Redis / RabbitMQ / Spring Boot
-> POST /api/admin/stocks/preload
-> 使用 HTTP 或 JMeter 提交异步下单
-> 执行 phase5-after-test.sql 检查结果
```

## 当前限制

- 一个订单只购买一个票档，明细直接保存在 `ticket_order`，不使用 `ticket_order_item`。
- 演出票档为查询缓存；订单库存变化后当前未自动清理缓存，验收库存请以 MySQL 为准或先删除相关 Redis key。
- 当前为模拟支付；RabbitMQ 可靠投递与数据库事务尚未做到完全一致。
- 当前已使用本地消息表降低 MQ 投递风险，但 `SENT` 仍是应用发送成功语义，还不是严格 Broker Confirm 回调落库。
- 固定窗口限流存在窗口边界突刺问题。
- 幂等 Token 当前使用 `hasKey + delete`，不是严格原子操作，后续可用 Lua 优化。
- 消费失败已有 DLQ 兜底，但缺少独立告警、人工补偿后台和完整失败重试治理。
- 订单超时时间以 `RabbitMqConstant.ORDER_TIMEOUT_TTL_MILLIS` 和订单 `expire_time` 为准。

## 后续规划

完整 Publisher Confirm Callback、local_message 管理接口、消费失败告警、人工补偿后台、Lua 限流/幂等、缓存失效策略、登录鉴权、监控看板、更多自动化测试。
