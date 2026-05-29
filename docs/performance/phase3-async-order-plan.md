# 第三阶段异步下单压测方案

## 为什么异步下单接口应该更快

同步下单接口在 HTTP 请求中完成校验、扣库存、创建订单和发送超时消息。

异步下单接口只做：

```text
保存 ticket_order_request -> 发送 MQ -> 返回 requestId
```

真正的扣库存和创建订单由消费者异步处理，因此接口响应时间通常应该更短，也更适合削峰。

## 压测前准备

1. 启动 MySQL、Redis、RabbitMQ。
2. 启动 Spring Boot。
3. 确认 `ticket_order_request` 表存在。
4. 确认 `order.async.queue` 有消费者。
5. 准备足够库存。
6. 压测前清理 Redis 防重复提交 key，或使用不同用户/票档组合。

## 重置库存

在 MySQL Workbench 执行，按需替换 `ticket_category_id` 和库存值：

```sql
UPDATE ticket_stock
SET total_stock = 10000,
    available_stock = 10000,
    locked_stock = 0,
    sold_stock = 0,
    updated_at = NOW()
WHERE ticket_category_id = 2;
```

可选清理测试数据：

```sql
DELETE o
FROM ticket_order o
JOIN ticket_order_request r ON r.order_id = o.id
WHERE r.request_id LIKE 'REQ%';

DELETE FROM ticket_order_request
WHERE request_id LIKE 'REQ%';
```

## 运行 k6

安装 k6 后执行：

```bash
k6 run docs/performance/phase3-async-order-k6.js
```

指定参数：

```bash
BASE_URL=http://localhost:8081 USER_ID=1 SHOW_ID=1 SESSION_ID=1 TICKET_CATEGORY_ID=2 \
k6 run docs/performance/phase3-async-order-k6.js
```

脚本包含两个场景：

| 场景 | 并发 | 时长 |
|---|---:|---:|
| `vus_10_30s` | 10 VUs | 30 秒 |
| `vus_50_30s` | 50 VUs | 30 秒 |

脚本只压测：

```http
POST /api/orders/async
```

不轮询结果。结果统一用 SQL 和 RabbitMQ 管理台检查。

## 观察指标

- 请求成功率
- 平均响应时间
- P95
- P99
- RabbitMQ `order.async.queue` 积压
- `ticket_order_request` 的 `SUCCESS` / `FAILED` 数量
- `ticket_order` 创建数量
- 库存是否守恒

## SQL 检查

执行：

```sql
SELECT status, COUNT(*)
FROM ticket_order_request
GROUP BY status;

SELECT status, COUNT(*)
FROM ticket_order
GROUP BY status;

SELECT ticket_category_id,
       total_stock,
       available_stock,
       locked_stock,
       sold_stock,
       available_stock + locked_stock + sold_stock AS calculated_total
FROM ticket_stock
WHERE ticket_category_id = 2;
```

完整检查脚本见：

```text
docs/sql/phase3-async-order-check.sql
```

## 如何判断异步削峰有效

可以认为有效的现象：

- HTTP 响应时间稳定，P95 没有明显飙升。
- 高并发下 `POST /api/orders/async` 仍能快速返回。
- RabbitMQ 队列允许短暂积压，但消费者能持续消化。
- `ticket_order_request` 最终从 `PROCESSING` 转为 `SUCCESS` 或 `FAILED`。
- 库存守恒。

## 当前方案限制

- Redis 防重复提交会限制同一个用户短时间重复提交同一个票档，不适合用完全相同请求体压极限吞吐。
- 数据库事务和 MQ 发送不是同一个事务。
- 暂未实现本地消息表。
- 暂未实现消费者重试次数控制。
- 暂未实现限流。
- 暂未使用 Lua 或 Redis 原子库存扣减。
