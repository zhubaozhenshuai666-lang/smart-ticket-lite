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

## 技术栈

Java 17、Spring Boot 3.x、Spring MVC、MyBatis-Plus、MySQL 8.x、Redis、RabbitMQ、Maven、Lombok。

## 本地启动

环境要求：JDK 17、MySQL 8.x、Redis、RabbitMQ、Maven。

1. 启动 MySQL，并准备 `smart_ticket_lite` 数据库。仓库当前未提供全量初始建表脚本；已有第一阶段业务表时，仅对尚未存在的第二阶段字段执行 [phase2-alter.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase2-alter.sql)。
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

## 核心流程

```text
选择演出与票档 -> 创建订单并锁库存 -> 支付 / 取消 / 超时关闭
```

异步下单链路：

```text
POST /api/orders/async
-> 保存 ticket_order_request(PROCESSING)
-> 发送 order.async.create 消息
-> 返回 requestId
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

## 当前限制

- 一个订单只购买一个票档，明细直接保存在 `ticket_order`，不使用 `ticket_order_item`。
- 演出票档为查询缓存；订单库存变化后当前未自动清理缓存，验收库存请以 MySQL 为准或先删除相关 Redis key。
- 当前为模拟支付；RabbitMQ 可靠投递与数据库事务尚未做到完全一致。
- 异步下单当前没有本地消息表，数据库事务和 MQ 投递仍存在一致性风险。
- 消费失败重试、死信失败处理、限流和告警仍是后续增强点。
- 订单超时时间以 `RabbitMqConstant.ORDER_TIMEOUT_TTL_MILLIS` 和订单 `expire_time` 为准。

## 第四阶段规划

本地消息表、Publisher Confirm、消费失败重试、死信失败队列、限流、Lua 扣库存、缓存失效策略、登录鉴权、更多自动化测试。
