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
- 支付单 `payment_order` 与 mock-pay 模拟支付回调
- RabbitMQ TTL + 死信队列自动关闭超时订单
- 定时任务兜底关闭过期待支付订单

第三阶段：

- `POST /api/orders/async` 异步提交下单请求
- 快速返回 `requestId`，不在接口线程中创建正式订单
- `ticket_order_request` 记录异步下单处理状态
- RabbitMQ 消费者异步扣库存并创建订单
- `GET /api/order-requests/{requestId}` 查询异步下单结果
- 异步订单创建成功后继续进入 `PENDING_PAYMENT` 状态，沿用支付、取消、超时关闭流程

第四阶段：

- Actuator 健康检查与基础指标
- 接口耗时日志和慢接口 warn 日志
- Redis Lua 令牌桶限流：IP 级、接口级、用户级、票档级
- 下单幂等 Token：同步下单和异步下单均需携带一次性 token
- 库存 `version` 字段维护，辅助并发排查和后续乐观锁升级
- 数据库索引优化 SQL 与 EXPLAIN 慢 SQL 分析
- JMeter 异步下单压测方案、压测前后 SQL 和结果模板

第五阶段：

- Redis 库存预热与 Redis Lua 原子预扣库存，使用 `requestId` 防止重复预扣
- 异步下单接入 `ticket_order_request` 状态机：`INIT`、`PRE_DEDUCTED`、`QUEUED`、`PROCESSING`、`SUCCESS`、`FAILED`、`COMPENSATED`
- Redis 预扣失败不发送 MQ，Redis 已扣但后续失败会立即释放或记录待补偿
- 本地消息表 `local_message`，保存待发送 MQ 消息
- 后台定时任务扫描本地消息表并发送 RabbitMQ
- RabbitMQ Publisher Confirm / ReturnsCallback / Confirm 超时扫描
- 异步下单消费者有限重试、异常分类、`dead_letter_message` 死信落库与人工处理入口
- Redis / MySQL 库存一致性巡检，考虑在途预扣量并记录 `stock_consistency_record`
- Redis 修复使用 Lua CAS + Delta，补偿动作写入 `stock_compensation_record`
- failed request Redis 预扣兜底补偿
- Redis Lua 令牌桶多维限流：用户、IP、接口、票档
- 热点票档 `ticket:soldout:{ticketCategoryId}` 快速失败，售罄后不创建 request、不写 local_message、不进 MQ
- 第五阶段压测计划、JMeter 脚本、压测前后 SQL 和验收材料

阶段 3 - 后台治理：

- `user_account.role_code` 轻量角色模型：`USER`、`ADMIN`、`OPERATOR`
- `/api/admin/**` 服务端权限保护，普通购票用户不能访问后台治理接口
- `ADMIN` 可执行消息重试、死信处理、库存修复、失败请求补偿等高风险操作
- `OPERATOR` 可查看后台数据，并执行库存一致性检查类低风险运营操作
- `admin_operation_log` 记录高风险后台操作审计，不记录 token/password
- 后台演出、场次、票档管理接口，资源状态为 `DRAFT/PUBLISHED/OFFLINE`
- 后台库存初始化、增量调整、Redis 安全预热和库存差异查询
- 用户侧只查询 `PUBLISHED` 资源，下架资源不会继续进入下单归属校验
- 库存预热按 `MySQL available_stock - 在途预扣量` 计算，Redis 已存在时使用 Lua CAS + Delta，库存恢复后清理 soldout

阶段 4 - 支付安全边界：

- `mock-pay` 仍然只是本地模拟支付，不接真实第三方 SDK
- 模拟支付回调必须携带 HMAC-SHA256 签名、timestamp 和 nonce，避免裸接口被随意调用
- `payment_callback_log` 记录每次回调原文、签名校验结果和处理结果，签名失败也落库
- `payment_flow_log` 记录支付单创建、成功、失败、幂等重复、关闭等状态流转
- `ticket_order` 保存演出名、场次时间、票档名、下单单价和总金额快照，历史订单不跟随后续票档改价变化

阶段 4 - 观测和告警预留：

- Actuator 仅暴露 `health/info/metrics`，不打开 `env/beans` 等敏感端点
- Micrometer 注册订单、异步请求、限流、soldout 等业务 Counter
- `local_message.DEAD`、死信 PENDING、库存差异 PENDING、补偿 FAILED 通过 Gauge 暴露
- 后台 `GET /api/admin/ops/metrics-summary` 汇总核心运维指标，普通 USER 不能访问
- 告警规则文档说明哪些指标需要人工或后续 Prometheus/Grafana 告警

## 技术栈

Java 17、Spring Boot 3.x、Spring MVC、MyBatis-Plus、MySQL 8.x、Redis、RabbitMQ、Maven、Lombok。

## 本地环境要求

- JDK 17
- Maven 3.9+
- MySQL 8.x
- Redis 6+
- RabbitMQ 3.x

## 数据库初始化

完整初始化脚本位于 [docs/sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql)。

1. 创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS smart_ticket_lite
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
```

2. 执行建表脚本：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p smart_ticket_lite < docs/sql/schema.sql
```

3. 执行基础数据脚本：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p smart_ticket_lite < docs/sql/data.sql
```

初始化测试 ID：

| 数据 | ID |
|---|---:|
| `userId` | 1 |
| `adminUserId` | 2 |
| `operatorUserId` | 3 |
| `showId` | 1 |
| `sessionId` | 1 |
| 看台票 `ticketCategoryId` | 1 |
| 内场票 `ticketCategoryId` | 2 |
| VIP 票 `ticketCategoryId` | 3 |

## 本地配置与启动

默认激活 `local` profile。仓库提供 [application-local.example.yml](/Users/zewbao/Desktop/smart-ticket-lite/src/main/resources/application-local.example.yml)，本地真实配置文件 [application-local.yml](/Users/zewbao/Desktop/smart-ticket-lite/src/main/resources/application-local.yml) 已在 `.gitignore` 中忽略。

1. 如需重新生成本地配置，可从示例文件复制：

```bash
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
```

2. 设置本机连接信息。不要把真实密码写入仓库，推荐使用环境变量：

```bash
export SMART_TICKET_DB_PASSWORD='你的本地 MySQL 密码'
export SMART_TICKET_REDIS_PASSWORD='你的本地 Redis 密码，如无密码可留空'
export SMART_TICKET_RABBITMQ_PASSWORD='你的本地 RabbitMQ 密码'
export SMART_TICKET_JWT_SECRET='至少32字节的本地JWT签名密钥'
```

3. 启动 Redis：

```bash
redis-server
```

4. 启动 RabbitMQ，并开启管理台：

```bash
rabbitmq-server
rabbitmq-plugins enable rabbitmq_management
```

管理台地址：[http://localhost:15672](http://localhost:15672)，账号密码以你的本地 RabbitMQ 配置为准。

5. 启动项目：

```bash
mvn spring-boot:run
```

服务默认地址：`http://localhost:8081`。

## 核心接口说明与 HTTP 测试

常用接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/register` | 用户注册，密码使用 BCrypt 存储 |
| `POST` | `/api/auth/login` | 用户登录，返回 JWT |
| `POST` | `/api/auth/logout` | 当前 token 退出登录，写入 Redis 黑名单 |
| `GET` | `/api/users/me` | 根据 Bearer token 查询当前用户 |
| `GET` | `/api/users/{id}` | 查询测试用户 |
| `GET` | `/api/shows` | 查询演出列表 |
| `GET` | `/api/shows/{id}` | 查询演出详情、场次、票档 |
| `GET` | `/api/orders/idempotency-token` | 登录后获取一次性下单幂等 token |
| `POST` | `/api/orders` | 已废弃，仅保留为本地调试/兼容入口，不作为抢票主链路 |
| `POST` | `/api/orders/async` | 高并发购票主链路，登录后异步提交下单请求，请求体不需要传 `userId` |
| `GET` | `/api/order-requests/{requestId}` | 查询当前用户的异步下单结果 |
| `GET` | `/api/users/me/orders` | 查询当前用户订单列表 |
| `POST` | `/api/payments/create` | 为当前用户订单创建支付单 |
| `GET` | `/api/payments/{paymentNo}` | 查询当前用户支付单 |
| `POST` | `/api/payments/mock-pay` | 本地模拟支付成功/失败回调，必须携带内部签名 |
| `POST` | `/api/orders/{orderId}/pay` | 旧直接支付接口，已废弃，不再改订单为 PAID |
| `POST` | `/api/orders/{orderId}/cancel` | 取消当前用户待支付订单 |
| `GET` | `/api/orders/{orderId}` | 查询当前用户订单详情 |

后台接口需要 Bearer token，且必须具备后台角色：

| 角色 | 能力 |
|---|---|
| `USER` | 普通购票用户，不能访问 `/api/admin/**` |
| `OPERATOR` | 可访问后台查询接口，并可执行库存一致性检查 |
| `ADMIN` | 可执行消息重试、死信处理、库存修复、失败请求补偿等高风险操作 |

阶段 3 后台权限说明见 [phase3-task-a-admin-auth-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase3-task-a-admin-auth-report.md)，学习笔记见 [phase3-task-a-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase3-task-a-learning.md)。

阶段 3 后台运营管理说明见 [phase3-task-b-admin-business-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase3-task-b-admin-business-report.md)，学习笔记见 [phase3-task-b-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase3-task-b-learning.md)。常用后台运营接口：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/admin/shows` | `ADMIN/OPERATOR` | 后台查询全部状态演出 |
| `POST` | `/api/admin/shows` | `ADMIN` | 创建草稿演出 |
| `POST` | `/api/admin/shows/{showId}/publish` | `ADMIN` | 上架演出 |
| `POST` | `/api/admin/shows/{showId}/offline` | `ADMIN` | 下架演出 |
| `GET` | `/api/admin/shows/{showId}/sessions` | `ADMIN/OPERATOR` | 后台查询演出场次 |
| `POST` | `/api/admin/shows/{showId}/sessions` | `ADMIN` | 创建草稿场次 |
| `GET` | `/api/admin/sessions/{sessionId}/ticket-categories` | `ADMIN/OPERATOR` | 后台查询票档 |
| `POST` | `/api/admin/sessions/{sessionId}/ticket-categories` | `ADMIN` | 创建草稿票档 |
| `POST` | `/api/admin/ticket-categories/{ticketCategoryId}/stock/init` | `ADMIN` | 初始化库存 |
| `POST` | `/api/admin/ticket-categories/{ticketCategoryId}/stock/adjust` | `ADMIN` | 增量调整库存 |
| `POST` | `/api/admin/ticket-categories/{ticketCategoryId}/stock/preheat` | `ADMIN/OPERATOR` | 按在途预扣量安全预热 Redis |
| `GET` | `/api/admin/ticket-categories/{ticketCategoryId}/stock` | `ADMIN/OPERATOR` | 查询 MySQL/Redis/在途预扣库存视图 |
| `GET` | `/api/admin/stocks` | `ADMIN/OPERATOR` | 查询所有库存视图 |
| `GET` | `/api/admin/ops/metrics-summary` | `ADMIN/OPERATOR` | 查询核心业务和异常存量指标摘要 |

第二阶段同步订单流程：在 IDEA 打开 [phase2-full-flow.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-full-flow.http)，从上到下依次点击请求左侧绿色运行按钮。

第三阶段异步下单流程：在 IDEA 打开 [phase3-async-order-full-flow.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase3-async-order-full-flow.http)，先提交异步下单，复制返回的 `requestId` 查询结果；当结果为 `SUCCESS` 后，再复制返回的 `orderId` 继续支付、取消或等待超时关闭。

阶段 4B 主链路收敛后，高并发购票主链路只走异步下单。`POST /api/orders` 已废弃，只保留为本地调试和历史兼容入口，不参与压测，不建议写进简历主链路。压测脚本和项目答辩应统一描述为：

```text
登录 -> 幂等 token -> 多维限流 -> soldout 快速失败 -> Redis 预扣
-> ticket_order_request -> local_message Outbox -> RabbitMQ
-> 消费者创建订单 -> payment_order -> mock-pay
```

阶段 4B 报告见 [phase4-task-b-main-flow-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-b-main-flow-report.md)，学习笔记见 [phase4-task-b-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-b-learning.md)。

接口文档见 [phase2-api.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-api.md)。

阶段 1 认证加固测试见 [phase1-auth-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase1-auth-api.http)，认证说明见 [phase1-auth-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase1-auth-report.md)。

阶段 1 订单权限测试见 [phase1-order-permission-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase1-order-permission-api.http)，订单权限改造说明见 [phase1-order-permission-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase1-order-permission-report.md)。

阶段 1 支付闭环测试见 [phase1-payment-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase1-payment-api.http)，支付闭环说明见 [phase1-payment-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase1-payment-report.md)。阶段 4D 后，`mock-pay` 不再是裸接口：请求体需要 `paymentNo/success/timestamp/nonce/signature`，签名算法和边界说明见 [phase4-task-d-payment-security-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-d-payment-security-report.md)。

第三阶段 RabbitMQ 检查见 [phase3-rabbitmq-check.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/mq/phase3-rabbitmq-check.md)。

压测说明见 [phase3-async-order-plan.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase3-async-order-plan.md)。

第四阶段 JMeter 压测入口：

- 测试计划：[phase4-async-order-test.jmx](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/jmeter/phase4-async-order-test.jmx)
- 压测说明：[phase4-jmeter-test-plan.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-jmeter-test-plan.md)
- 压测前 SQL：[phase4-jmeter-before.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-jmeter-before.sql)
- 压测后 SQL：[phase4-jmeter-after.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-jmeter-after.sql)

第四阶段验收入口：

- 观测能力：[phase4-observability.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-observability.md)
- 阶段 4E 观测增强报告：[phase4-task-e-observability-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-e-observability-report.md)
- 阶段 4E 观测学习笔记：[phase4-task-e-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-e-learning.md)
- 阶段 4E 告警规则：[phase4-alert-rules.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-alert-rules.md)
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
- 阶段 2 Task A+ Redis 预扣报告：[phase2-task-a-redis-prededuct-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-a-redis-prededuct-report.md)
- 阶段 2 Task A+ 学习笔记：[phase2-task-a-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-a-learning.md)

第五阶段可靠消息与压测入口：

- 本地消息表 SQL：[phase5-local-message.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase5-local-message.sql)
- 可靠消息 HTTP 测试：[phase5-reliable-message-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase5-reliable-message-api.http)
- 可靠消息设计：[phase5-reliable-message-design.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-reliable-message-design.md)
- 可靠消息流程说明：[phase5-reliable-message-flow-summary.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase5-reliable-message-flow-summary.md)
- 阶段 2 Task B+ 可靠消息报告：[phase2-task-b-reliable-message-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-b-reliable-message-report.md)
- 阶段 2 Task B+ 学习笔记：[phase2-task-b-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-b-learning.md)
- 阶段 2 Task C+ 消费治理报告：[phase2-task-c-consumer-dlq-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-c-consumer-dlq-report.md)
- 阶段 2 Task C+ 学习笔记：[phase2-task-c-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-c-learning.md)
- 消费死信 HTTP 测试：[phase2-consumer-dlq-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-consumer-dlq-api.http)
- 阶段 2 Task D+ 库存一致性报告：[phase2-task-d-stock-consistency-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-d-stock-consistency-report.md)
- 阶段 2 Task D+ 学习笔记：[phase2-task-d-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-d-learning.md)
- 库存一致性 HTTP 测试：[phase2-stock-consistency-api.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-stock-consistency-api.http)
- 阶段 2 Task E+ 限流与 soldout 报告：[phase2-task-e-rate-limit-soldout-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-task-e-rate-limit-soldout-report.md)
- 阶段 2 收口总结：[phase2-summary.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-summary.md)
- 阶段 2 学习导读：[phase2-learning-guide.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-learning-guide.md)
- 阶段 2 k6 压测脚本：[phase2-async-order-rate-limit-k6.js](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase2-async-order-rate-limit-k6.js)
- 阶段 2 压测前 SQL：[phase2-pressure-before.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase2-pressure-before.sql)
- 阶段 2 压测后 SQL：[phase2-pressure-after.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase2-pressure-after.sql)
- 阶段 2 压测报告模板：[phase2-pressure-test-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase2-pressure-test-report.md)
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
POST /api/admin/stocks/consistency/check/{ticketCategoryId}
POST /api/admin/stocks/consistency/check-all
GET  /api/admin/stocks/consistency-records
POST /api/admin/stocks/consistency-records/{id}/repair
POST /api/admin/stocks/consistency-records/{id}/ignore
POST /api/admin/stocks/failed-requests/compensate
GET  /api/admin/local-messages
GET  /api/admin/dead-letters
POST /api/admin/dead-letters/{id}/retry
POST /api/admin/dead-letters/{id}/ignore
POST /api/admin/dead-letters/{id}/resolve
GET  /api/admin/stocks/{ticketCategoryId}/consistency
GET  /api/admin/ops/metrics-summary
```

这些接口当前位于 `/api/admin/**`，受 JWT 拦截器保护，但还没有 RBAC 管理员角色，生产环境不能直接暴露。

## 测试命令

```bash
mvn test
mvn -q -DskipTests package
```

当前测试包含 Service 层单元测试、Controller MockMvc 测试、MQ 消费者单元测试、Redis 幂等 token 语义测试、JWT/登录失败限制测试、Mapper SQL 合同测试，以及阶段 4 新增的 Testcontainers 集成测试入口。

阶段 4 Testcontainers 说明：

- 报告：[phase4-task-a-testcontainers-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-a-testcontainers-report.md)
- 学习笔记：[phase4-task-a-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-a-learning.md)

阶段 4 主链路收敛与真实压测入口：

- 主链路收敛报告：[phase4-task-b-main-flow-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-b-main-flow-report.md)
- 主链路学习笔记：[phase4-task-b-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-b-learning.md)
- 阶段 4C 压测执行报告：[phase4-task-c-pressure-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-c-pressure-report.md)
- 阶段 4C 压测学习笔记：[phase4-task-c-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-c-learning.md)
- 阶段 4C k6 脚本：[phase4-async-order-main-flow-k6.js](/Users/zewbao/Desktop/smart-ticket-lite/docs/performance/phase4-async-order-main-flow-k6.js)
- 阶段 4C 压测前 SQL：[phase4-pressure-before.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-pressure-before.sql)
- 阶段 4C 压测后 SQL：[phase4-pressure-after.sql](/Users/zewbao/Desktop/smart-ticket-lite/docs/sql/phase4-pressure-after.sql)
- 阶段 4C 压测报告：[phase4-pressure-test-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-pressure-test-report.md)
- 阶段 4D 支付安全边界报告：[phase4-task-d-payment-security-report.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-d-payment-security-report.md)
- 阶段 4D 支付建模学习笔记：[phase4-task-d-learning.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/phase4-task-d-learning.md)

阶段 4C 当前没有伪造性能数据：如果本机没有安装 `k6` 或没有准备好 MySQL / Redis / RabbitMQ / Spring Boot 环境，报告中的 TPS、P95、P99、错误率必须保持“未执行，待本地压测填写”。

本地执行真实 MySQL / Redis / RabbitMQ 容器集成测试前，需要先启动 Docker Desktop：

```bash
docker ps
mvn -Dtest='*IntegrationTest' test
```

如果当前机器没有 Docker，Testcontainers 集成测试会被跳过。此时 `mvn test` 成功只能代表单元测试和 Mock 测试通过，不能代表真实容器链路已经跑通。

## 核心流程

```text
选择演出与票档 -> 创建订单并锁库存 -> 创建支付单 -> mock-pay 回调支付 / 取消 / 超时关闭
```

支付链路：

```text
POST /api/payments/create
-> 创建 payment_order(INIT)，金额来自 ticket_order.total_amount 快照
-> POST /api/payments/mock-pay，校验 HMAC-SHA256 签名和 timestamp/nonce
-> 写 payment_callback_log 回调原文
-> payment_order INIT -> SUCCESS
-> 写 payment_flow_log 支付状态流转
-> ticket_order PENDING_PAYMENT -> PAID
-> ticket_stock locked_stock -> sold_stock
```

异步下单链路：

```text
POST /api/orders/async
-> 创建 ticket_order_request(INIT)
-> Redis Lua 使用 requestId 原子预扣库存
-> ticket_order_request 标记 PRE_DEDUCTED
-> 保存 local_message(INIT)
-> ticket_order_request 标记 QUEUED
-> 返回 requestId
-> LocalMessagePublishTask 定时发送 order.async.create 消息
-> local_message SENDING -> SENT
-> Broker ack 后 local_message 标记 CONFIRMED
-> AsyncCreateOrderConsumer 消费消息
-> 条件更新 ticket_order_request 为 PROCESSING，抢占处理权
-> MySQL 条件扣库存
-> 创建 ticket_order(PENDING_PAYMENT)
-> ticket_order_request 标记 SUCCESS 并写入 orderId
-> 写入 ORDER_TIMEOUT_CLOSE local_message
-> LocalMessagePublishTask 投递订单超时关闭延迟消息
-> 用户通过 GET /api/order-requests/{requestId} 查询结果
```

| 状态 | 含义 |
|---|---|
| `PENDING_PAYMENT` | 已创建，等待支付 |
| `PAID` | 已支付 |
| `CANCELLED` | 用户主动取消 |
| `CLOSED` | 超时关闭 |

支付单状态：

| 状态 | 含义 |
|---|---|
| `INIT` | 支付单已创建，等待支付 |
| `PAYING` | 支付处理中，当前阶段预留 |
| `SUCCESS` | 支付成功 |
| `FAILED` | 支付失败 |
| `CLOSED` | 订单取消或超时后关闭支付单 |

| 操作 | available_stock | locked_stock | sold_stock |
|---|---:|---:|---:|
| 创建订单 | 减少 | 增加 | 不变 |
| 支付订单 | 不变 | 减少 | 增加 |
| 取消/超时关闭 | 增加 | 减少 | 不变 |

异步请求状态：

| 状态 | 含义 |
|---|---|
| `INIT` | 请求已创建，尚未预扣 Redis 库存 |
| `PRE_DEDUCTED` | Redis 库存已预扣，正式订单尚未创建 |
| `QUEUED` | 消息已进入发送链路，等待消费者 |
| `PROCESSING` | 消费者正在处理 |
| `SUCCESS` | 下单成功，`orderId` 有值 |
| `FAILED` | 下单失败，查看 `failReason` |
| `COMPENSATED` | 下单失败且 Redis 预扣已释放 |
| `CANCELLED` | 预留状态，当前没有用户取消异步请求场景 |

RabbitMQ 在第三阶段的作用：

- `order.async.queue`：削峰异步创建订单。
- `smart-ticket.order.timeout.delay.queue`：订单创建后延迟触发超时关闭检查。
- `smart-ticket.order.timeout.dead.queue`：TTL 到期后的真正消费队列。

RabbitMQ 在第五阶段的作用：

- `local_message`：发送前先落库，避免请求成功但 MQ 消息无法追踪。
- `LocalMessagePublishTask`：扫描 `INIT/FAILED` 消息，抢占为 `SENDING` 后投递 RabbitMQ。
- `Publisher Confirm`：Broker ack 后将消息标记为 `CONFIRMED`。
- `ReturnsCallback`：exchange 存在但 routingKey 不可达时标记 `FAILED`。
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

## 当前项目边界

- 当前没有前端页面，接口以 HTTP 文件和 API 调用验证为主。
- 当前已有登录注册与 JWT 认证能力；用户侧订单接口统一从 `UserContext` 获取当前用户，不再信任请求体或路径中的 `userId`。
- JWT 已包含 `jti`，logout 会将 `jti` 写入 Redis 黑名单；登录失败次数使用 Redis 做临时锁定。
- 当前已有轻量 `USER/ADMIN/OPERATOR` 角色模型和 `/api/admin/**` 服务端保护，但没有完整 RBAC、权限码、菜单权限、刷新 token、OAuth2、短信验证码和图形验证码。
- 一个订单只购买一个票档，明细直接保存在 `ticket_order`，不使用 `ticket_order_item`。
- 演出票档为查询缓存；订单库存变化后当前未自动清理缓存，验收库存请以 MySQL 为准或先删除相关 Redis key。
- 当前有 `payment_order` 支付单和 mock-pay 模拟回调，但没有真实三方支付、退款单、出票和核销。
- 旧 `/api/orders/{orderId}/pay` 不再作为支付主链路，调用会提示先创建支付单。
- 同步下单 `/api/orders` 已废弃，只能作为本地调试/兼容入口；高并发购票主链路只走 `/api/orders/async`。
- 当前是单体应用，没有拆分微服务。
- 当前已使用本地消息表和 Publisher Confirm 降低 MQ 投递风险；异步创单和订单超时关闭消息都进入 `local_message`。`SENT` 只表示已调用发送，`CONFIRMED` 才表示 Broker ack，不代表消费者已经创建订单或关闭订单成功。
- 当前库存巡检会按 `expectedRedisAvailable = mysqlAvailable - inFlightDeductedQuantity` 判断差异；自动巡检默认关闭，自动修复默认关闭。
- 下单入口限流已升级为 Redis Lua 令牌桶，并接入用户、IP、接口、票档多维保护；生产阈值仍需要真实压测校准。
- soldout 标记只是售罄快速失败优化，不替代 Redis 预扣和 MySQL 条件库存更新。
- 幂等 Token 当前使用 Redis Lua 原子消费，避免旧方案的并发非原子问题。
- 消费失败已有有限重试、死信落库和人工 retry/ignore/resolve；后台高风险操作已写 `admin_operation_log`，但仍缺完整 RBAC、告警通知、巡检自动补偿和完整运维后台。
- 订单超时时间以 `OrderConstant.ORDER_TIMEOUT_MINUTES` / `OrderConstant.ORDER_TIMEOUT_TTL_MILLIS` 和订单 `expire_time` 为准。

## 后续阶段计划

- 阶段 2：完善库存一致性治理，补 Redis/MySQL 对账、缓存失效、库存回补和压测后的数据校验。
- 阶段 3：强化 MQ 可靠消息，落地 Publisher Confirm Callback、失败重试、告警和人工补偿入口。
- 阶段 4：补充演出管理、库存调整审批、监控看板和更接近真实票务平台的运营能力。
