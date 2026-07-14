# SmartTicket Lite

面向演出票务场景的 Spring Boot 单体服务。项目的交易主链路为异步下单：入口完成资格校验和 Redis 库存预扣后返回 `requestId`，由消息消费者创建正式订单，再进入支付、取消和超时关闭流程。

> 当前默认消息实现为 RocketMQ 事务消息。Kafka、Redis Stream 和 Outbox 均保留为可切换实现，不能与默认链路混为一谈。

## 技术栈

- Java 21、Spring Boot 3.5、Spring MVC、Spring AOP、MyBatis-Plus
- MySQL 8、Redis、Caffeine
- RocketMQ（默认）、Kafka、Redis Stream（可选）
- Micrometer、Spring Boot Actuator、JUnit 5、Testcontainers、JMeter

## 主链路

```text
JWT 鉴权
  -> 获取一次性幂等 Token
  -> 提交 POST /api/orders/async
  -> 风控 / 防重复 / 限流 / 等待室 / 在途容量控制
  -> Redis Lua 原子预扣（支持库存分桶）
  -> RocketMQ 事务消息
  -> 消费者幂等抢占请求处理权
  -> MySQL 条件扣减库存 + 创建 ticket_order
  -> 更新 ticket_order_request 为 SUCCESS
  -> 创建支付单 / 模拟支付回调
  -> 支付成功确认库存；取消或超时关闭释放库存
```

### 下单与创单

1. 用户通过 `GET /api/orders/idempotency-token` 获取一次性下单 Token。
2. `POST /api/orders/async` 从 JWT 上下文获取用户身份，依次执行风控、售罄快速失败、重复提交保护、用户/IP/活动/票档限流、活动降级、演出归属校验、在途容量控制与可选等待室校验。
3. 服务使用 `requestId` 作为幂等键调用 Redis Lua 脚本，原子完成库存校验、预扣和预扣记录写入；库存分桶开启时仅在有限探测窗口内选择一个 bucket 扣减。
4. 默认 RocketMQ 模式先发送半消息，再执行 Redis 预扣与事务标记写入；Broker 回查时根据预扣记录决定提交或回滚消息。
5. 消费者通过请求状态机与 SQL 条件更新抢占处理权，过滤重复消息；在一个事务中执行 MySQL 条件扣库存、订单快照与正式订单落库、请求成功状态回写。
6. 创单失败、业务拒绝或 `PROCESSING` 超时会记录失败原因；已预扣的 Redis 库存按 `requestId` 幂等补偿，并记录补偿状态和死信信息。

### 状态模型

异步请求 `ticket_order_request`：

```text
QUEUED -> PROCESSING -> SUCCESS
                     -> FAILED -> COMPENSATED
```

正式订单 `ticket_order`：

```text
PENDING_PAYMENT -> PAID
PENDING_PAYMENT -> CANCELLED
PENDING_PAYMENT -> CLOSED
```

Redis 预扣是入口侧资格控制，不是最终库存事实。消费者仍须用 `available_stock >= quantity` 的 MySQL 条件更新完成最终扣减，避免缓存重建或人工修复期间出现超卖。

## 已实现能力

- **下单保护**：JWT 身份识别、一次性幂等 Token、重复提交保护、风控、等待室、活动降级、在途容量与多维 Lua 令牌桶限流。
- **库存控制**：Redis Lua 原子预扣、热点库存分桶、售罄快速失败、失败补偿、Redis/MySQL/在途预扣量一致性巡检，以及 Lua CAS + Delta 修复。
- **消息处理**：RocketMQ 事务消息和顺序消费；支持 Kafka 分区消费、有限重试及 DLT，也支持 Redis Stream 和 Outbox 投递模式。
- **可靠事件**：订单创建、支付成功和库存变更领域事件写入本地消息表，支持发送抢占、重试退避、确认超时和人工死信处理。
- **支付与订单闭环**：支付单幂等创建、HMAC-SHA256 模拟回调验签、支付流水/回调审计、取消订单和延迟消息超时关闭。
- **运营后台**：演出、场次、票档、库存预热与调整；ADMIN/OPERATOR 权限控制和高风险操作审计。
- **可观测性**：Actuator 健康检查与指标、订单/请求业务 Counter、慢调用日志、MQ 消费追踪和后台指标汇总。

## 默认配置

配置入口：[application.yml](src/main/resources/application.yml)。可通过环境变量覆盖全部关键配置。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8081` | HTTP 服务端口 |
| `smart-ticket.async-order-submit.publisher-mode` | `rocketmq` | 异步创单投递模式 |
| `smart-ticket.async-order-submit.rocket-mq-transaction-message-enabled` | `true` | RocketMQ 事务消息开关 |
| `smart-ticket.order-timeout.publisher-mode` | `rocketmq` | 超时订单消息模式 |
| `smart-ticket.stock-bucket.enabled` | `true` | Redis 库存分桶开关 |
| `smart-ticket.waiting-room.enabled` | `false` | 等待室开关 |
| `smart-ticket.rate-limit.enabled` | `true` | 下单限流开关 |

`application-flash-sale.yml` 提供抢票场景的覆盖配置，例如更高的库存桶数量、等待室和更严格的限流参数。

## 本地启动

### 1. 准备依赖

- JDK 21
- Maven 3.9+
- MySQL 8+
- Redis 6+
- RocketMQ NameServer 与 Broker（默认模式）

Kafka 仅在将异步创单或超时消息模式切换为 `kafka` 时需要启动。

### 2. 初始化数据库

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -e '
CREATE DATABASE IF NOT EXISTS smart_ticket_lite
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;'

mysql -h 127.0.0.1 -P 3306 -u root -p smart_ticket_lite < docs/sql/schema.sql
mysql -h 127.0.0.1 -P 3306 -u root -p smart_ticket_lite < docs/sql/data.sql
```

如需补充索引，请先检查目标库现有索引，再执行 `docs/sql/performance-indexes.sql`。

### 3. 配置本地环境

```bash
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml

export SMART_TICKET_DB_PASSWORD='your-db-password'
export SMART_TICKET_REDIS_PASSWORD=''
export SMART_TICKET_JWT_SECRET='a-local-secret-with-at-least-32-bytes'
export SMART_TICKET_ROCKETMQ_NAME_SERVER='localhost:9876'
```

`application-local.yml` 已被忽略，不应提交真实密码或密钥。

### 4. 启动服务

```bash
mvn spring-boot:run
```

健康检查：

```bash
curl http://127.0.0.1:8081/actuator/health
```

## 核心接口

除注册、登录和 Actuator 外，用户接口需携带 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/register` | 注册用户 |
| `POST` | `/api/auth/login` | 登录并获取 JWT |
| `POST` | `/api/auth/logout` | 当前 Token 退出登录 |
| `GET` | `/api/shows` | 查询已发布演出 |
| `GET` | `/api/shows/{id}` | 查询演出详情、场次和票档 |
| `GET` | `/api/orders/idempotency-token` | 获取一次性下单 Token |
| `POST` | `/api/orders/async` | 异步下单主入口，返回 `requestId` |
| `GET` | `/api/order-requests/{requestId}` | 查询异步创单结果 |
| `GET` | `/api/users/me/orders` | 查询当前用户订单 |
| `POST` | `/api/payments/create` | 为当前用户订单创建支付单 |
| `POST` | `/api/payments/mock-pay` | 本地模拟支付回调，需携带签名 |
| `POST` | `/api/orders/{id}/cancel` | 取消当前用户待支付订单 |

`POST /api/orders` 和 `POST /api/orders/{id}/pay` 为废弃兼容入口，不属于抢票主链路。

后台接口位于 `/api/admin/**`：`USER` 无后台权限，`OPERATOR` 可执行查询和低风险运营操作，`ADMIN` 可执行库存调整、消息重试、死信处理和补偿等高风险操作。

接口请求示例位于 [docs/api](docs/api)。

## 消息模式

| 场景 | 模式 | 说明 |
| --- | --- | --- |
| 异步创单 | `rocketmq`（默认） | RocketMQ 事务消息、顺序消费与事务回查 |
| 异步创单 | `kafka` | 按业务键分区，支持有限重试和 DLT |
| 异步创单 | `redis-stream` | Consumer Group 轮询与确认消费 |
| 异步创单 | `outbox` | 本地消息表记录待发送指令，由定时任务投递 Kafka |
| 超时关闭 | `rocketmq`（默认） | 延迟消息关闭未支付订单；定时任务兜底扫描 |
| 领域事件 | 本地消息表 | 订单、支付和库存事件可靠投递 Kafka |

切换模式前应同时准备对应中间件、Topic/Consumer Group 配置和监控，不应只修改单个环境变量。

## 验证与压测

```bash
mvn test
```

项目包含单元测试、集成测试、Mapper SQL 契约测试和 JMeter 异步下单脚本。JMeter 脚本位于 `scripts/jmeter/`，运行及环境准备脚本位于 `scripts/load/`，正式压测计划见 [docs/performance/formal-jmeter-pressure-test-plan.md](docs/performance/formal-jmeter-pressure-test-plan.md)。

仓库现有报告用于验证链路和记录本机测试，不代表生产环境容量；在未完成独立压测机、多实例应用、Redis/MySQL/Kafka 或 RocketMQ 集群验证前，不应将其表述为生产吞吐结论。

## 目录说明

```text
src/main/java/.../controller   HTTP 与后台管理入口
src/main/java/.../service      下单、库存、支付、缓存和治理服务
src/main/java/.../mq           消息生产、消费、重试与批量调度
src/main/resources/lua         Redis 库存、补偿、限流与幂等脚本
src/main/resources/mapper      MyBatis SQL 映射
docs/sql                       建表、初始化数据和索引脚本
docs/api                       HTTP 接口示例
scripts/load                   压测数据和环境准备脚本
```

## 边界说明

- `mock-pay` 是本地模拟支付回调，不接入真实第三方支付 SDK。
- Redis 预扣用于削峰和快速失败，MySQL 条件扣减才是正式订单的库存事实。
- 事务消息、补偿、巡检解决的是可恢复的一致性问题，不等同于跨 Redis、MySQL 和 MQ 的严格分布式强一致。
