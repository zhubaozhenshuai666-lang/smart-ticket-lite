# smart-ticket-lite

## 1. 项目简介

`smart-ticket-lite` 是一个基于 Java 17、Spring Boot 3.x、Spring MVC、MyBatis-Plus 和 MySQL 的单体票务系统。

本项目是票务系统的第一阶段版本，目标是先完成核心业务建模、数据库设计、基础接口和本地可运行能力。

第一阶段不追求高并发秒杀能力，而是优先保证：

- 业务模型清晰
- 数据库关系合理
- 项目结构规范
- 基础 CRUD 可用
- 下单流程能够跑通
- 后续方便扩展 Redis、MQ、秒杀和权限系统

---

## 2. 技术栈

- Java 17
- Spring Boot 3.x
- Spring MVC
- MyBatis-Plus
- MySQL 8.x
- Maven
- Lombok
- JUnit 5

---

## 3. 第一阶段目标

第一阶段主要完成以下内容：

- 搭建 Spring Boot 单体后端项目
- 配置 MySQL 数据源
- 配置 MyBatis-Plus
- 设计核心业务表
- 实现用户、场馆、演出、场次、票档、订单模块
- 提供基础 REST API
- 实现统一响应结构
- 实现基础异常处理
- 实现基础参数校验
- 支持本地启动和调试

---

## 4. 核心业务模块

第一阶段包含以下模块：

| 模块 | 说明 |
|---|---|
| 用户模块 | 维护购票用户基础信息 |
| 场馆模块 | 维护演出场馆信息 |
| 演出模块 | 维护演出基础信息 |
| 场次模块 | 维护某个演出的具体演出时间和场馆 |
| 票档模块 | 维护某个场次下的票价和库存 |
| 订单模块 | 处理用户下单、查询订单、取消订单等基础流程 |

---

## 5. 核心数据表

第一阶段使用以下核心表：

| 表名 | 说明 |
|---|---|
| user_account | 用户表 |
| venue | 场馆表 |
| show_info | 演出信息表 |
| performance_session | 演出场次表 |
| ticket_category | 票档表 |
| ticket_order | 订单主表 |
| ticket_order_item | 订单明细表 |

不建议使用 `user` 作为表名，避免和数据库关键字或系统表产生冲突。

---

## 6. 核心表关系

核心关系如下：

```text
user_account 1 ---- N ticket_order

show_info 1 ---- N performance_session

venue 1 ---- N performance_session

performance_session 1 ---- N ticket_category

performance_session 1 ---- N ticket_order

ticket_order 1 ---- N ticket_order_item

ticket_category 1 ---- N ticket_order_item
```

关键字段关系：

```text
performance_session.show_id = show_info.id

performance_session.venue_id = venue.id

ticket_category.session_id = performance_session.id

ticket_order.user_id = user_account.id

ticket_order.session_id = performance_session.id

ticket_order_item.order_id = ticket_order.id

ticket_order_item.ticket_category_id = ticket_category.id
```

---

## 7. 业务模型说明

### 7.1 演出和场次

`show_info` 表示演出本身，例如：

```text
周杰伦演唱会
```

`performance_session` 表示具体某一场，例如：

```text
2026-06-01 19:30 上海场
2026-06-02 19:30 上海场
2026-06-08 19:30 北京场
```

所以一个演出可以有多个场次。

---

### 7.2 场次和票档

`ticket_category` 表示某个场次下的票档。

例如某个场次下可以有：

```text
VIP 票：1280 元
一等票：880 元
二等票：580 元
```

其中：

```text
ticket_category.session_id = performance_session.id
```

也就是说，票档是属于具体场次的。

---

### 7.3 订单和订单明细

`ticket_order` 是订单主表，保存订单整体信息。

`ticket_order_item` 是订单明细表，保存用户具体买了哪个票档、买了几张、单价是多少。

订单明细中保存 `ticket_name` 和 `ticket_price` 是为了保留下单时的票档快照，避免后续票价修改影响历史订单。

---

## 8. 第一阶段下单流程

第一阶段下单流程如下：

```text
1. 用户选择场次
2. 用户选择票档
3. 用户提交购买数量
4. 系统校验用户、场次、票档是否存在
5. 系统校验票档库存是否充足
6. 系统扣减票档库存
7. 系统创建订单主表记录
8. 系统创建订单明细记录
9. 返回订单结果
```

第一阶段使用数据库事务保证下单过程的一致性。

---

## 9. 暂不实现的功能

第一阶段暂不实现以下功能：

- Redis 缓存
- MQ 消息队列
- Spring Security
- JWT 登录认证
- AI Agent
- 分布式锁
- 秒杀抢票
- 支付系统
- 真实短信验证码
- 文件上传
- 分库分表
- 微服务
- Docker 部署
- Kubernetes 部署

这些内容放到后续阶段逐步扩展。


## 10. 当前阶段目标总结

第一阶段只需要完成核心业务闭环：

```text
创建场馆
创建演出
创建场次
创建票档
创建用户
用户下单
扣减库存
生成订单
查询订单
取消订单
```

只要这个闭环可以稳定跑通，第一阶段目标就完成了。