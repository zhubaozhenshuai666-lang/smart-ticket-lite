# 第四阶段 Redis 限流设计

## 为什么需要限流

票务系统在开票、高峰查询和支付前后容易出现瞬时流量。限流的目标不是让系统永远不报错，而是在流量超过承载能力时优先保护核心资源，避免数据库、Redis、RabbitMQ 被打满。

第四阶段先准备通用限流组件，后续再接入具体接口。

## 固定窗口限流原理

固定窗口限流把时间切成一个个窗口。例如 10 秒内最多允许 20 次：

```text
rate:api:api:orders:async -> 1
rate:api:api:orders:async -> 2
...
rate:api:api:orders:async -> 20
rate:api:api:orders:async -> 21 拒绝
```

当 key 过期后，进入下一个窗口重新计数。

## Redis INCR + EXPIRE 的作用

`INCR` 用于递增访问次数。

`EXPIRE` 用于设置窗口过期时间。

流程：

```text
INCR key
如果结果是 1，设置 EXPIRE
如果结果 <= limit，放行
如果结果 > limit，拒绝
```

## 固定窗口的缺点

固定窗口实现简单，但存在边界突刺问题。

例如限制 10 秒 100 次，用户可能在第 9.9 秒打 100 次，又在第 10.1 秒打 100 次，短时间内形成 200 次请求。

第四阶段先接受这个缺点，因为它适合学习和基础保护。

## 为什么第四阶段先不用 Lua

Lua 可以把 `INCR` 和 `EXPIRE` 做成严格原子操作，但会增加脚本维护、调试和排查成本。

当前项目目标是先建立高并发保护的主流程：key 设计、规则设计、降级策略、接入位置和压测验证。

后续如果压测发现固定窗口不够，再升级为 Lua 或滑动窗口。

## Redis 异常时的策略

当前建议 Redis 异常时降级放行。

原因：

```text
限流是保护能力，不应该因为 Redis 短暂异常导致所有接口不可用。
```

降级放行会打 warn 日志，方便排查 Redis 故障。

## 后续如何升级

后续可以逐步升级：

1. 接入 `POST /api/orders/async`。
2. 增加 IP 级限流。
3. 增加用户级限流。
4. 增加票档级限流。
5. 改成 Lua 原子限流。
6. 改成滑动窗口限流。
7. 增加限流命中指标和告警。

## 当前已接入的接口

拦截器保护路径：

```text
/api/orders/**
/api/order-requests/**
```

因此已覆盖：

```text
POST /api/orders
POST /api/orders/async
GET /api/order-requests/{requestId}
GET /api/orders/{id}
POST /api/orders/{id}/pay
POST /api/orders/{id}/cancel
```

下单入口额外在 Service 层接入：

```text
OrderServiceImpl.createOrder
OrderServiceImpl.submitAsyncOrder
```

## 当前限流 key 和阈值

| 类型 | Key 示例 | 阈值 |
|---|---|---|
| IP + URI | `rate:ip:127.0.0.1:api:orders:async` | 10 秒 20 次 |
| URI 全局 | `rate:api:api:orders:async` | 10 秒 200 次 |
| 用户下单 | `rate:user:1:order-submit` | 10 秒 5 次 |
| 票档下单 | `rate:ticket:2:order-submit` | 10 秒 50 次 |

## 如何测试

使用：

```text
docs/api/phase4-rate-limit-api.http
```

测试重点：

1. 快速连续调用 `POST /api/orders/async`，第 6 次左右会触发用户级限流。
2. 快速连续调用 `GET /api/order-requests/{requestId}` 超过 20 次，会触发 IP + URI 限流。
3. 快速连续调用支付或取消接口超过 20 次，会触发 IP + URI 限流。

查看 Redis key：

```bash
redis-cli KEYS 'rate:*'
redis-cli TTL 'rate:user:1:order-submit'
```

## 当前限制

- 当前是固定窗口算法，窗口边界可能存在瞬时突刺。
- 当前没有使用 Lua，`INCR` 和 `EXPIRE` 不是严格原子组合。
- 支付和取消接口当前请求路径没有 userId，所以只做 IP + URI 和接口级限流。
- 用户级和票档级限流放在 Service 层，因为需要读取请求体里的 `userId` 和 `ticketCategoryId`。
