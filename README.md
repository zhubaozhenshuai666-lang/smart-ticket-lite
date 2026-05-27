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

在 IDEA 打开 [phase2-full-flow.http](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-full-flow.http)，从上到下依次点击请求左侧绿色运行按钮。创建订单后，将返回的订单 `id` 更新到文件顶部对应变量。

接口文档见 [phase2-api.md](/Users/zewbao/Desktop/smart-ticket-lite/docs/api/phase2-api.md)。

当前测试超时约为 `1` 分钟，支付或主动取消请求请在创建订单后立即执行。

## 核心流程

```text
选择演出与票档 -> 创建订单并锁库存 -> 支付 / 取消 / 超时关闭
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

## 当前限制

- 一个订单只购买一个票档，明细直接保存在 `ticket_order`，不使用 `ticket_order_item`。
- 演出票档为查询缓存；订单库存变化后当前未自动清理缓存，验收库存请以 MySQL 为准或先删除相关 Redis key。
- 当前为模拟支付；RabbitMQ 可靠投递与数据库事务尚未做到完全一致。
- 测试配置的订单超时约为 `1` 分钟。

## 第三阶段规划

本地消息表与可靠投递、缓存失效策略、登录鉴权、更多自动化测试。
