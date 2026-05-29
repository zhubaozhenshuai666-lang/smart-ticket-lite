# 第四阶段观测能力说明

## 为什么先做接口耗时统计

限流、幂等和压测之前，需要先能看见接口是否变慢。接口耗时日志可以快速判断瓶颈是在 HTTP 接口、数据库、Redis、RabbitMQ 还是下游业务逻辑。

## 查看 Actuator Health

```http
GET http://localhost:8081/actuator/health
```

健康检查会返回应用状态和基础组件状态。

## 查看 Actuator Metrics

```http
GET http://localhost:8081/actuator/metrics
```

该接口会列出当前可查看的指标名称。查看具体指标时，在路径后追加指标名，例如：

```http
GET http://localhost:8081/actuator/metrics/http.server.requests
```

## 查看接口耗时日志

请求普通业务接口后，控制台会输出类似日志：

```text
API request, method=GET, uri=/api/shows, status=200, costMs=35, clientIp=127.0.0.1
```

如果接口耗时超过 1000ms，会输出 warn 级别慢接口日志：

```text
Slow API request, method=POST, uri=/api/orders/async, status=200, costMs=1200, clientIp=127.0.0.1
```

日志不会打印请求体，避免泄露手机号、密码或其他敏感数据。

## 什么是慢接口

当前项目先把超过 1000ms 的接口定义为慢接口。后续压测时可以根据实际结果调整阈值，例如查询接口 300ms、下单接口 500ms。

## 为什么暂时不接 Prometheus

第四阶段先做本地可观测闭环：控制台日志 + Actuator 基础指标。Prometheus 和 Grafana 需要额外部署、采集和面板配置，适合在接口限流、压测和核心指标稳定后再接入。
