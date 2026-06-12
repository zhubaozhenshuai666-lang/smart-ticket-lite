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

        assertThatCode(() -> new AsyncOrderSubmitGuardrail(properties, environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownPublisherMode() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setPublisherMode("kafka");
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, environment).afterPropertiesSet())
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

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct-rabbit");
    }

    @Test
    void flashSaleProfileRejectsRequestPrePersist() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setPersistRequestBeforePublish(true);

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, flashSaleEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预落库");
    }

    @Test
    void flashSaleProfileRejectsSynchronousConfirmWait() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setDirectRabbitWaitForConfirm(true);

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, flashSaleEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同步 confirm");
    }

    @Test
    void flashSaleProfileRejectsTooSmallInFlightWindow() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();
        properties.setMaxInFlightPerTicketCategory(20_000L);

        assertThatThrownBy(() -> new AsyncOrderSubmitGuardrail(properties, flashSaleEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-flight");
    }

    @Test
    void flashSaleProfileAllowsExplicitFastPipeline() {
        AsyncOrderSubmitProperties properties = flashSaleProperties();

        assertThatCode(() -> new AsyncOrderSubmitGuardrail(properties, flashSaleEnvironment()).afterPropertiesSet())
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
}
