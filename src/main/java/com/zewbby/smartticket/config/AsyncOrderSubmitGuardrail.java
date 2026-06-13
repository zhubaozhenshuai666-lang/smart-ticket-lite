package com.zewbby.smartticket.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class AsyncOrderSubmitGuardrail implements InitializingBean {

    private static final String FLASH_SALE_PROFILE = "flash-sale";

    private static final long FLASH_SALE_MIN_IN_FLIGHT_PER_TICKET_CATEGORY = 50_000L;

    private final AsyncOrderSubmitProperties properties;

    private final Environment environment;

    public AsyncOrderSubmitGuardrail(AsyncOrderSubmitProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validatePublisherMode();
        if (isFlashSaleProfileActive()) {
            validateFlashSaleProfile();
        }
    }

    private void validatePublisherMode() {
        String publisherMode = properties.getPublisherMode();
        if (AsyncOrderSubmitProperties.PUBLISHER_MODE_OUTBOX.equalsIgnoreCase(publisherMode)
                || AsyncOrderSubmitProperties.PUBLISHER_MODE_DIRECT_RABBIT.equalsIgnoreCase(publisherMode)
                || AsyncOrderSubmitProperties.PUBLISHER_MODE_REDIS_STREAM.equalsIgnoreCase(publisherMode)) {
            return;
        }
        throw new IllegalStateException("smart-ticket.async-order-submit.publisher-mode 只允许 outbox、direct-rabbit 或 redis-stream");
    }

    private boolean isFlashSaleProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(FLASH_SALE_PROFILE::equalsIgnoreCase);
    }

    private void validateFlashSaleProfile() {
        if (!properties.isDirectRabbitPublisherMode() && !properties.isRedisStreamPublisherMode()) {
            throw new IllegalStateException("flash-sale profile 必须使用 direct-rabbit 或 redis-stream 发布模式，不能继续走 Outbox 写放大路径");
        }
        if (properties.isPersistRequestBeforePublish()) {
            throw new IllegalStateException("flash-sale profile 必须关闭入口 ticket_order_request 预落库");
        }
        if (properties.isDirectRabbitWaitForConfirm()) {
            throw new IllegalStateException("flash-sale profile 必须关闭 direct-rabbit 同步 confirm 等待");
        }
        if (!properties.isInFlightControlEnabled()) {
            throw new IllegalStateException("flash-sale profile 必须开启 in-flight 控制，防止 MQ 和消费者被无限堆积打穿");
        }
        if (properties.getMaxInFlightPerTicketCategory() < FLASH_SALE_MIN_IN_FLIGHT_PER_TICKET_CATEGORY) {
            throw new IllegalStateException("flash-sale profile 单票档 in-flight 上限不能低于 "
                    + FLASH_SALE_MIN_IN_FLIGHT_PER_TICKET_CATEGORY);
        }
    }
}
