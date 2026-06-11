package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.async-order-submit")
public class AsyncOrderSubmitProperties {

    /**
     * 是否在入口发布消息前先写 ticket_order_request。
     *
     * 默认开启，保持可靠 Outbox 兼容链路；高并发活动可以关闭，让消费者根据消息补建请求记录，
     * 从入口链路中移除一次 MySQL insert。
     */
    private boolean persistRequestBeforePublish = true;

    /**
     * 异步下单消息发布模式。
     *
     * outbox: 写 local_message 后可靠投递，可靠性强但 DB 写放大明显。
     * direct-rabbit: 入口直接发布到 RabbitMQ 分片队列，减少 local_message 写入，适合压测和高峰资格事件链路。
     */
    private String publisherMode = "outbox";

    /**
     * direct-rabbit 模式下是否等待 Broker Confirm。
     *
     * 开启时可靠性更强但单请求延迟更高；关闭时吞吐更高，但需要外部补偿/巡检承接发布失败。
     */
    private boolean directRabbitWaitForConfirm = true;

    private long directRabbitConfirmTimeoutMillis = 500L;

    /**
     * 是否启用异步下单 in-flight 退避。
     *
     * 它限制“Redis 已准备进入预扣/MQ，但消费者还没处理到终态”的请求数，防止热点票档把 MQ、消费者和 MySQL
     * 堆到不可恢复的延迟区间。
     */
    private boolean inFlightControlEnabled = true;

    private long maxInFlightPerTicketCategory = 20000L;

    private long inFlightCounterTtlSeconds = 600L;

    public boolean isPersistRequestBeforePublish() {
        return persistRequestBeforePublish;
    }

    public void setPersistRequestBeforePublish(boolean persistRequestBeforePublish) {
        this.persistRequestBeforePublish = persistRequestBeforePublish;
    }

    public String getPublisherMode() {
        return publisherMode;
    }

    public void setPublisherMode(String publisherMode) {
        this.publisherMode = publisherMode;
    }

    public boolean isDirectRabbitWaitForConfirm() {
        return directRabbitWaitForConfirm;
    }

    public void setDirectRabbitWaitForConfirm(boolean directRabbitWaitForConfirm) {
        this.directRabbitWaitForConfirm = directRabbitWaitForConfirm;
    }

    public long getDirectRabbitConfirmTimeoutMillis() {
        return directRabbitConfirmTimeoutMillis;
    }

    public void setDirectRabbitConfirmTimeoutMillis(long directRabbitConfirmTimeoutMillis) {
        this.directRabbitConfirmTimeoutMillis = directRabbitConfirmTimeoutMillis;
    }

    public boolean isInFlightControlEnabled() {
        return inFlightControlEnabled;
    }

    public void setInFlightControlEnabled(boolean inFlightControlEnabled) {
        this.inFlightControlEnabled = inFlightControlEnabled;
    }

    public long getMaxInFlightPerTicketCategory() {
        return Math.max(1L, maxInFlightPerTicketCategory);
    }

    public void setMaxInFlightPerTicketCategory(long maxInFlightPerTicketCategory) {
        this.maxInFlightPerTicketCategory = maxInFlightPerTicketCategory;
    }

    public long getInFlightCounterTtlSeconds() {
        return Math.max(30L, inFlightCounterTtlSeconds);
    }

    public void setInFlightCounterTtlSeconds(long inFlightCounterTtlSeconds) {
        this.inFlightCounterTtlSeconds = inFlightCounterTtlSeconds;
    }
}
