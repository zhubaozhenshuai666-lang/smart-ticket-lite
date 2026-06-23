# Phase 2 Pressure Test Report

## Scope

The pressure test targets `POST /api/orders/async` as the only high-concurrency ticket purchase entry.

The deprecated synchronous `POST /api/orders` endpoint is not included in the pressure script.

## Metrics

- QPS
- P95 latency
- P99 latency
- Error rate
- Redis stock key changes
- MySQL `ticket_order_request` status distribution
- MQ consumer lag
