package com.zewbby.smartticket.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncOrderSubmitGuardrailTest {

    @Test
    void normalProfileAllowsDefaultReliableOutboxMode() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> new AsyncOrderSubmitGuardrail(properties, new OrderTimeoutProperties(), environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownPublisherMode() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setPublisherMode("rocket");
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, new OrderTimeoutProperties(), environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher-mode");
    }

    @Test
    void flashSaleProfileRejectsOutboxMode() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_OUTBOX);
        properties.setPersistRequestBeforePublish(false);
        properties.setDirectRabbitWaitForConfirm(false);
        properties.setMaxInFlightPerTicketCategory(100_000L);
        MockEnvironment environment = flashSaleEnvironment();

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, flashSaleOrderTimeoutProperties(), environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct-rabbit");
    }

    @Test
    void flashSaleProfileRejectsRequestPrePersist() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setPersistRequestBeforePublish(true);

        assertThatThrownBy(() -> newGuardrail(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预落库");
    }

    @Test
    void flashSaleProfileRejectsSynchronousConfirmWait() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setDirectRabbitWaitForConfirm(true);

        assertThatThrownBy(() -> newGuardrail(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同步 confirm");
    }

    @Test
    void flashSaleProfileRejectsTooSmallInFlightWindow() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setMaxInFlightPerTicketCategory(20_000L);

        assertThatThrownBy(() -> newGuardrail(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-flight");
    }

    @Test
    void flashSaleProfileRejectsOrderTimeoutOutboxWriteAmplification() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        OrderTimeoutProperties orderTimeoutProperties = new OrderTimeoutProperties();
        orderTimeoutProperties.setDelayMessageEnabled(true);

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, orderTimeoutProperties, flashSaleEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("订单超时延迟消息");
    }

    @Test
    void flashSaleProfileAllowsExplicitFastPipeline() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();

        assertThatCode(() -> newGuardrail(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void flashSaleProfileAllowsRedisStreamEventPipeline() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_REDIS_STREAM);

        assertThatCode(() -> newGuardrail(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void flashSaleProfileAllowsKafkaEventPipeline() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_KAFKA);
        properties.setDirectRabbitWaitForConfirm(true);

        assertThatCode(() -> newGuardrail(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private AsyncOrderSubmitProperties flashSaleProperties() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_DIRECT_RABBIT);
        properties.setPersistRequestBeforePublish(false);
        properties.setDirectRabbitWaitForConfirm(false);
        properties.setInFlightControlEnabled(true);
        properties.setMaxInFlightPerTicketCategory(100_000L);
        return properties;
    }

    private MockEnvironment flashSaleEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("flash-sale");
        return environment;
    }

    private OrderTimeoutProperties flashSaleOrderTimeoutProperties() {
        OrderTimeoutProperties orderTimeoutProperties = new OrderTimeoutProperties();
        orderTimeoutProperties.setDelayMessageEnabled(false);
        return orderTimeoutProperties;
    }

    private AsyncOrderSubmitGuardrail newGuardrail(AsyncOrderSubmitProperties properties) {
        return new AsyncOrderSubmitGuardrail(properties, flashSaleOrderTimeoutProperties(), flashSaleEnvironment());
    }
}
