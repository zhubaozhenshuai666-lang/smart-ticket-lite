# 第三阶段 RabbitMQ 验证说明

## 管理台

地址：http://localhost:15672

本地默认账号通常是：

```text
guest / guest
```

## 异步下单 MQ

异步下单使用：

| 类型 | 名称 |
|---|---|
| Exchange | `order.async.exchange` |
| Queue | `order.async.queue` |
| RoutingKey | `order.async.create` |
| DLX | `order.async.dlx.exchange` |
| DLQ | `order.async.dlq` |

验证方式：

1. 打开 RabbitMQ 管理台。
2. 进入 `Exchanges`，搜索 `order.async.exchange`。
3. 确认绑定到 `order.async.queue`，Routing key 为 `order.async.create`。
4. 进入 `Queues and Streams`，搜索 `order.async.queue`。
5. 调用 `POST /api/orders/async`。
6. 如果消费者正常，`Ready` 会短暂增加后回到 `0`。
7. 如果消息堆积，`Ready` 会持续大于 `0`。

## 订单超时关闭 MQ

订单超时关闭使用：

| 类型 | 名称 |
|---|---|
| Delay Exchange | `smart-ticket.order.timeout.delay.exchange` |
| Delay Queue | `smart-ticket.order.timeout.delay.queue` |
| Delay RoutingKey | `smart-ticket.order.timeout.delay` |
| Dead Exchange | `smart-ticket.order.timeout.dead.exchange` |
| Dead Queue | `smart-ticket.order.timeout.dead.queue` |
| Dead RoutingKey | `smart-ticket.order.timeout.dead` |

创建订单成功后，会发送超时检查消息到延迟队列。TTL 到期后，消息进入死信队列，由消费者调用 `closeTimeoutOrder(orderId)`。

## 常见问题

### 消息进队列但不消费

检查：

- Spring Boot 是否启动成功。
- `AsyncCreateOrderConsumer` 是否有启动日志。
- RabbitMQ 连接配置是否正确。
- `order.async.queue` 是否有消费者数量。
- 项目是否连接到了同一个 RabbitMQ vhost。

### 消费者报错

检查应用日志中的：

```text
Failed to consume async create order message
```

再查：

```sql
SELECT request_id, status, fail_reason
FROM ticket_order_request
ORDER BY id DESC
LIMIT 10;
```

### 请求一直 PROCESSING

常见原因：

- 消息没有进入 `order.async.queue`。
- 消费者没有启动。
- 消费者异常后消息反复重试。
- 数据库表 `ticket_order_request` 未创建或字段不完整。

### 库存没扣

先确认请求是否成功：

```sql
SELECT request_id, status, order_id, fail_reason
FROM ticket_order_request
ORDER BY id DESC
LIMIT 10;
```

如果 `status = FAILED` 且 `fail_reason = 库存不足`，说明条件扣库存没有成功。

### 订单没创建

如果请求状态是 `PROCESSING`，先看 MQ 消费情况。

如果请求状态是 `FAILED`，看 `fail_reason`。

如果请求状态是 `SUCCESS` 但订单不存在，说明存在严重一致性问题，需要检查事务、Mapper 和数据库日志。
