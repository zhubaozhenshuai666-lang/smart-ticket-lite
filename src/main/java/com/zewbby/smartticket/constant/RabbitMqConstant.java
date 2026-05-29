package com.zewbby.smartticket.constant;

public final class RabbitMqConstant {

    public static final int ORDER_TIMEOUT_TTL_MILLIS = 10 * 60 * 1000;

    public static final String ORDER_TIMEOUT_DELAY_EXCHANGE = "smart-ticket.order.timeout.delay.exchange";

    public static final String ORDER_TIMEOUT_DELAY_QUEUE = "smart-ticket.order.timeout.delay.queue";

    public static final String ORDER_TIMEOUT_DELAY_ROUTING_KEY = "smart-ticket.order.timeout.delay";

    public static final String ORDER_TIMEOUT_DEAD_EXCHANGE = "smart-ticket.order.timeout.dead.exchange";

    public static final String ORDER_TIMEOUT_DEAD_QUEUE = "smart-ticket.order.timeout.dead.queue";

    public static final String ORDER_TIMEOUT_DEAD_ROUTING_KEY = "smart-ticket.order.timeout.dead";

    public static final String ORDER_ASYNC_EXCHANGE = "order.async.exchange";

    public static final String ORDER_ASYNC_QUEUE = "order.async.queue";

    public static final String ORDER_ASYNC_ROUTING_KEY = "order.async.create";

    public static final String ORDER_ASYNC_DLX_EXCHANGE = "order.async.dlx.exchange";

    public static final String ORDER_ASYNC_DLQ = "order.async.dlq";

    public static final String ORDER_ASYNC_DLQ_ROUTING_KEY = "order.async.failed";

    private RabbitMqConstant() {
    }
}
