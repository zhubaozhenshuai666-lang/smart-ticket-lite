# SmartTicket Lite 项目简历稿与架构梳理

这份文档按当前代码结构整理，不按旧截图口径写。旧口径里有两处不能继续照抄：项目已经升级到 Java 21；高并发购票主链路默认走 RocketMQ，RabbitMQ 不能再写成当前主链路。

## 当前实现口径

- 主入口：`POST /api/orders/async`。`POST /api/orders` 只保留本地调试和历史兼容，不能写成高并发主链路。
- 抢票 profile：默认激活 `local,flash-sale`，`flash-sale` 下开启快速链路，入口不预写 `ticket_order_request`，由消费者根据 MQ 消息补建处理记录。
- 消息链路：异步创单和超时关单默认使用 RocketMQ；代码保留 Kafka、Outbox、Redis Stream 的可切换实现，领域事件通过 `local_message` Outbox 投递到 Kafka。
- 库存模型：Redis Lua 做入口预扣和快速失败，MySQL 条件扣减/分桶扣减是最终库存事实。
- 运行治理：JWT 鉴权、后台角色、操作审计、Micrometer 指标、死信消息、库存一致性巡检、补偿记录都已经落表或落指标。

## 简历版

项目简介：SmartTicket Lite 是面向演唱会/赛事抢票场景设计的高并发票务系统，围绕登录鉴权、演出场次票档缓存、等待室准入、多维限流、Redis Lua 分桶预扣、RocketMQ 顺序异步创单、MySQL 条件扣减、支付回调、超时关单、库存巡检补偿和后台治理构建完整交易闭环。系统重点解决抢票洪峰下入口削峰、热点库存、重复提交、消息重复消费、订单状态一致性和异常补偿问题。

技术栈：Java 21、Spring Boot 3.5.x、Spring MVC、MyBatis-Plus、MySQL 8、Redis、RocketMQ、Kafka、Caffeine、Lua、Micrometer、JWT、JUnit 5、Testcontainers、JMeter。

项目亮点：

- 高并发异步下单主链路：将同步下单改造为“幂等 token + 等待室准入 + 多维限流 + Redis 预扣 + MQ 异步创单”，接口线程只完成资格校验、削峰和消息投递，返回 `requestId` 供前端轮询，避免抢票洪峰直接打穿 MySQL。
- 热点库存分桶与双层防超卖：Redis 侧使用 Lua 脚本按票档 bucket 原子预扣，并通过 `requestId` 记录预扣/补偿幂等；消费端再执行 MySQL `available_stock >= quantity` 条件扣减或 `ticket_stock_bucket` 分桶扣减，形成 Redis 抗峰值、MySQL 保最终一致的双层防线。
- 快速链路与队列水位保护：在 `flash-sale` 配置下入口不再预写 `ticket_order_request`，减少一次数据库写放大；消费者根据消息补建处理记录，并通过 Redis in-flight 水位、活动维度隔离、降级开关和消费者并发参数控制 MQ、应用和数据库积压。
- 幂等消费与状态机治理：围绕 `ticket_order_request` 构建 `PROCESSING/SUCCESS/FAILED/COMPENSATED/CANCELLED` 状态机，消费者通过状态抢占处理权，重复消息和终态请求直接跳过；异常按可重试/业务失败/数据不一致分类，失败请求进入死信表或补偿链路。
- 可靠消息与领域事件：异步创单、超时关单支持 RocketMQ/Kafka/Outbox 可配置发布；领域事件通过 `local_message` 本地消息表、事务后投递、确认超时扫描和重试机制发布到 Kafka，降低业务事务成功但消息丢失的风险。
- 支付与订单生命周期闭环：支付单金额来自订单快照，mock 支付回调接入 HMAC 签名、timestamp、nonce 和回调日志；订单支付成功确认库存，取消/超时关闭释放库存，RocketMQ 延时消息配合定时扫描兜底，避免待支付订单长期占用库存。
- 库存一致性与补偿体系：库存巡检按 `expectedRedisAvailable = MySQL available - 在途预扣量` 判断差异，修复 Redis 时使用 Lua CAS + Delta，失败请求补偿通过 `requestId` 幂等释放预扣库存，并记录 `stock_consistency_record`、`stock_compensation_record` 便于追踪。
- 缓存与读路径优化：使用 Caffeine 缓存演出-场次-票档关系、订单快照和用户状态，将抢票入口的关系校验、价格快照和用户状态判断前置到 JVM 内存，降低高峰期对 MySQL 的重复查询压力。
- 后台治理和可观测性：实现 `ADMIN/OPERATOR/USER` 角色隔离，后台支持演出、场次、票档、库存、死信和补偿处理；高风险操作写入 `admin_operation_log`，通过 Micrometer 暴露订单、异步请求、限流、死信、库存差异和补偿失败等指标。

## 从 0 梳理架构

### 1. 系统分层

- `controller`：HTTP 入口，包括用户、演出、订单、支付和后台治理接口。
- `auth` / `ratelimit` / `aop`：鉴权、角色拦截、限流、审计和关键链路耗时监控。
- `service` / `service.impl`：业务编排层，下单、库存、支付、补偿、缓存、等待室、容量评估都在这一层完成。
- `mq`：异步创单、超时关单、支付补偿消息体和消费者。
- `task`：本地消息投递、超时订单扫描、库存一致性扫描、缓存预热等定时任务。
- `mapper` / `resources/mapper`：MyBatis-Plus Mapper 和手写 SQL。
- `domain` / `enums`：实体、DTO、VO、事件对象和业务状态枚举。
- `resources/lua`：Redis 原子限流、预扣、释放、分桶搬迁和 CAS 修复脚本。

### 2. 核心数据表

- 用户与资源：`user_account`、`venue`、`show_info`、`performance_session`、`ticket_category`。
- 库存：`ticket_stock`、`ticket_stock_bucket`。
- 订单：`ticket_order_request`、`ticket_order`。
- 支付：`payment_order`、`payment_callback_log`、`payment_flow_log`。
- 消息与补偿：`local_message`、`dead_letter_message`、`stock_consistency_record`、`stock_compensation_record`。
- 后台审计：`admin_operation_log`。

### 3. 抢票主链路

```text
登录获取 JWT
-> 预取下单幂等 token
-> 可选等待室 admission token
-> POST /api/orders/async
-> 风控与多维限流
-> soldout 快速失败
-> Caffeine 校验演出/场次/票档关系
-> Redis Lua 分桶预扣库存
-> RocketMQ 顺序消息
-> 消费者补建/抢占 ticket_order_request
-> MySQL 条件扣库存或分桶扣库存
-> 创建 ticket_order 待支付订单
-> 投递超时关单消息
-> 前端按 requestId 查询结果
```

### 4. 库存闭环

入口 Redis 预扣只负责削峰，不能代表订单成功。消费者创建订单时必须再次扣 MySQL；支付成功后确认订单，取消或超时关闭时释放库存。库存巡检不是简单比较 Redis 和 MySQL，而是扣除在途预扣后再判断差异，修复必须用 CAS，不能直接 `SET` 覆盖。

### 5. 消息闭环

交易命令默认走 RocketMQ，保证异步创单和超时关单能按业务维度顺序处理。Outbox 不是当前抢票默认主链路，但用于可靠投递领域事件，也可作为兼容发布模式。消费者不相信 MQ 只投一次，所以通过状态抢占、终态跳过、死信落库和补偿记录处理重复、乱序和异常。

### 6. 支付闭环

用户只能为自己的订单创建支付单，金额来自订单快照。mock 支付虽然不是第三方支付，但会改变订单和库存，所以回调必须校验签名、时间窗口和 nonce；每次回调和状态流转都写日志，后续才能定位重复回调、伪造回调和补偿失败。

## 面试讲法

30 秒版本：这个项目不是普通 CRUD 票务系统，核心是抢票交易链路。我把下单入口从同步写订单改成异步资格申请，入口用幂等 token、等待室、多维限流和 Redis Lua 分桶预扣削峰，然后用 RocketMQ 推给消费者创建正式订单。消费者再用 MySQL 条件扣减保证最终不超卖，支付、超时关单、库存释放、巡检补偿和后台治理构成完整闭环。

2 分钟版本：用户登录后先拿一次性幂等 token，进入抢票接口时先经过风控、限流、等待室和 soldout 快速失败。通过 Caffeine 校验演出关系后，Redis Lua 按票档分桶原子预扣库存，成功后发送 RocketMQ 顺序消息。消费者按 `requestId` 抢占或补建 `ticket_order_request`，对重复消息和终态请求直接跳过，然后执行 MySQL 条件扣库存或 bucket 扣库存，创建待支付订单和订单快照，并发送延时关单消息。支付回调做签名、timestamp、nonce 校验，成功后更新订单和支付单；取消或超时关闭会释放库存。异常侧有死信表、失败请求 Redis 预扣补偿、库存一致性巡检、Lua CAS 修复和后台审计，所以能讲清楚从入口削峰到最终一致的完整链路。

## 不要再写的内容

- 不要写“Java 17”，当前 `pom.xml` 是 Java 21。
- 不要把 RabbitMQ 写成当前主链路，当前配置默认是 RocketMQ；Kafka/Outbox 是可切换和领域事件链路。
- 不要宣传同步下单接口，`POST /api/orders` 已废弃。
- 不要写“Redis 扣库存后订单就成功”，Redis 只是入口资格占用，MySQL 条件扣减才是最终事实。
- 不要只写“解决高并发”，要说清楚限流、等待室、Redis 预扣、分桶、MQ、MySQL 条件更新、状态机和补偿分别解决什么问题。
