# SmartTicket Lite 第二阶段接口文档

基础地址：`http://localhost:8081`  
统一响应：`{"code":200,"message":"success","data":...}`；业务异常通常返回 `code=400`。

## 用户接口

### 查询用户

- URL：`/api/users/{id}`
- Method：`GET`
- 请求参数：路径参数 `id`，用户 ID
- 请求 JSON：无
- 正常场景：测试前确认用户是否存在
- 异常场景：用户不存在

```json
{"code":200,"message":"success","data":{"id":1,"username":"zewbby","phone":"13800000001"}}
```

### 创建用户

- URL：`/api/users`
- Method：`POST`
- 请求参数：`username`、`phone`
- 请求 JSON：见下方
- 正常场景：创建购票用户
- 异常场景：用户名或手机号校验失败、手机号已存在

```json
{"username":"phase2tester","phone":"13900000001"}
```

```json
{"code":200,"message":"success","data":{"id":2,"username":"phase2tester","phone":"13900000001"}}
```

## 演出查询接口

### 查询演出列表

- URL：`/api/shows`
- Method：`GET`
- 请求参数：无
- 请求 JSON：无
- 正常场景：浏览可选择的演出
- 异常场景：当前实现通常返回空数组

```json
{"code":200,"message":"success","data":[{"id":1,"title":"SmartTicket 测试演唱会","artist":"测试乐队","city":"上海","venueName":"梅赛德斯奔驰文化中心"}]}
```

### 查询演出详情

- URL：`/api/shows/{id}`
- Method：`GET`
- 请求参数：路径参数 `id`，演出 ID
- 请求 JSON：无
- 正常场景：返回场馆及场次信息；相同查询可命中 Redis 缓存
- 异常场景：演出不存在，返回 `演出不存在`

```json
{"code":200,"message":"success","data":{"id":1,"title":"SmartTicket 测试演唱会","artist":"测试乐队","city":"上海","venueName":"梅赛德斯奔驰文化中心","sessions":[]}}
```

### 查询场次

- URL：`/api/shows/{id}/sessions`
- Method：`GET`
- 请求参数：路径参数 `id`，演出 ID
- 请求 JSON：无
- 正常场景：查看演出的可购票场次；可命中 Redis 缓存
- 异常场景：演出不存在

```json
{"code":200,"message":"success","data":[{"id":1,"showId":1,"venueName":"梅赛德斯奔驰文化中心","city":"上海","startTime":"2026-06-20T19:30:00","ticketCategories":[]}]}
```

### 查询票档

- URL：`/api/sessions/{sessionId}/ticket-categories`
- Method：`GET`
- 请求参数：路径参数 `sessionId`，场次 ID
- 请求 JSON：无
- 正常场景：查看票价与展示用 `availableStock`；结果缓存于 Redis
- 异常场景：不存在的场次当前可能返回空数组

```json
{"code":200,"message":"success","data":[{"id":2,"sessionId":1,"name":"内场票","price":880,"totalStock":10,"availableStock":10}]}
```

> 订单操作后当前不会主动清理演出查询缓存。核验库存变化前，请删除 `session:ticket-categories:{sessionId}` 缓存，或直接查询 MySQL。

## 订单接口

### 创建订单

> 阶段 4B 后，`POST /api/orders` 已废弃，仅保留为本地调试 / 历史兼容入口。高并发购票主链路只使用 `POST /api/orders/async`。

- URL：`/api/orders`
- Method：`POST`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：`showId`、`sessionId`、`ticketCategoryId`、`quantity`、`idempotencyToken`
- 请求 JSON：见下方
- 正常场景：本地调试创建待支付订单，锁定库存，并写入订单超时关闭 Outbox 消息
- 异常场景：未登录、演出场次票档关系不匹配、票档/库存不存在、库存不足、并发重复提交、本地消息写入失败

```json
{"showId":1,"sessionId":1,"ticketCategoryId":2,"quantity":1,"idempotencyToken":"token-from-/api/orders/idempotency-token"}
```

```json
{"code":200,"message":"success","data":{"id":30,"orderNo":"ST...","status":"PENDING_PAYMENT","expireTime":"2026-05-27T18:01:00","payTime":null}}
```

创建成功时库存变化：`available_stock - quantity`，`locked_stock + quantity`。
当前测试超时约为 `1` 分钟；支付或主动取消测试应在订单自动关闭前完成。

### 高并发异步下单

- URL：`/api/orders/async`
- Method：`POST`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：`showId`、`sessionId`、`ticketCategoryId`、`quantity`、`idempotencyToken`
- 正常场景：返回 `requestId`，后续通过 `/api/order-requests/{requestId}` 查询订单创建结果
- 主链路：限流、soldout 快速失败、Redis 预扣、`ticket_order_request`、`local_message`、RabbitMQ、消费者创建订单

### 查询订单

- URL：`/api/orders/{id}`
- Method：`GET`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：路径参数 `id`，订单 ID
- 请求 JSON：无
- 正常场景：查看当前登录用户自己的订单状态及时间信息
- 异常场景：订单不存在或不属于当前登录用户

```json
{"code":200,"message":"success","data":{"id":30,"status":"PENDING_PAYMENT","expireTime":"2026-05-27T18:01:00","payTime":null,"cancelTime":null,"closeTime":null}}
```

### 查询用户订单列表

- URL：`/api/users/me/orders`
- Method：`GET`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：无
- 请求 JSON：无
- 正常场景：查看当前登录用户的订单历史
- 异常场景：没有订单时返回空数组

```json
{"code":200,"message":"success","data":[{"id":30,"status":"PENDING_PAYMENT"}]}
```

旧路径 `/api/users/{userId}/orders` 暂时保留兼容，但会忽略路径中的 `userId`，只返回当前 token 用户的订单。

### 创建支付单

- URL：`/api/payments/create`
- Method：`POST`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：`orderId`、`channel`
- 请求 JSON：见下方
- 正常场景：为当前登录用户自己的 `PENDING_PAYMENT` 订单创建 `payment_order`
- 异常场景：订单不存在、不属于当前登录用户、已支付、已取消、已关闭或已过期

```json
{"orderId":30,"channel":"MOCK"}
```

```json
{"code":200,"message":"success","data":{"paymentNo":"PAY...","orderId":30,"amount":880.00,"channel":"MOCK","status":"INIT"}}
```

### mock-pay 支付回调

- URL：`/api/payments/mock-pay`
- Method：`POST`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：`paymentNo`、`success`
- 请求 JSON：见下方
- 正常场景：当前登录用户自己的支付单支付成功，订单 `PENDING_PAYMENT -> PAID`
- 异常场景：支付单不存在、不属于当前用户、支付单已关闭/失败、订单已取消/关闭

```json
{"paymentNo":"PAY...","success":true}
```

支付成功时库存变化：`locked_stock - quantity`，`sold_stock + quantity`。重复成功回调幂等返回，不重复流转库存。

旧接口 `/api/orders/{id}/pay` 已废弃，会提示“请先创建支付单后再支付”，不能绕过 `payment_order` 直接修改订单。

### 主动取消订单

- URL：`/api/orders/{id}/cancel`
- Method：`POST`
- 请求头：`Authorization: Bearer <token>`
- 请求参数：路径参数 `id`，订单 ID
- 请求 JSON：无
- 正常场景：仅当前登录用户自己的 `PENDING_PAYMENT` 订单可以取消
- 异常场景：订单不存在、不属于当前登录用户、重复取消，或订单已经支付/关闭

```json
{"code":200,"message":"success","data":{"id":31,"status":"CANCELLED","cancelTime":"2026-05-27T18:02:00","cancelReason":"用户主动取消"}}
```

取消成功时库存变化：`available_stock + quantity`，`locked_stock - quantity`。

## 自动超时关闭

超时关闭没有对外 Controller 接口。创建订单后消息进入 RabbitMQ TTL 队列；TTL 以 `OrderConstant.ORDER_TIMEOUT_TTL_MILLIS` 为准，到期后由死信消费者调用关闭逻辑，定时任务每分钟兜底扫描。

```json
{"code":200,"message":"success","data":{"id":32,"status":"CLOSED","closeTime":"2026-05-27T18:05:00","cancelReason":"订单超时未支付关闭"}}
```

超时关闭库存变化：`available_stock + quantity`，`locked_stock - quantity`。
