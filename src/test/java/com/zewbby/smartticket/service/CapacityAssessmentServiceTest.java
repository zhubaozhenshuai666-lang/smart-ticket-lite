package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityAssessmentServiceTest {

    @Test
    void assessOrderPipelineCapacityReportsMainBottleneckFromCurrentConfig() {
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        rateLimitProperties.setOrderApiRefillRatePerSecond(10000D);
        rateLimitProperties.setOrderTicketRefillRatePerSecond(2000D);
        MqConsumerProperties mqConsumerProperties = new MqConsumerProperties();
        mqConsumerProperties.setAsyncQueueShardCount(32);
        mqConsumerProperties.setMaxConcurrentConsumers(64);
        mqConsumerProperties.setPrefetchCount(10);
        AsyncOrderSubmitProperties asyncOrderSubmitProperties = new AsyncOrderSubmitProperties();
        asyncOrderSubmitProperties.setPersistRequestBeforePublish(false);
        asyncOrderSubmitProperties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_DIRECT_RABBIT);
        asyncOrderSubmitProperties.setDirectRabbitWaitForConfirm(false);
        asyncOrderSubmitProperties.setMaxInFlightPerTicketCategory(50000L);
        StockBucketProperties stockBucketProperties = new StockBucketProperties();
        stockBucketProperties.setDefaultBucketCount(64);
        stockBucketProperties.setActiveProbeCount(4);
        WaitingRoomProperties waitingRoomProperties = new WaitingRoomProperties();
        waitingRoomProperties.setEnabled(true);

        var service = new CapacityAssessmentService(
                rateLimitProperties,
                mqConsumerProperties,
                asyncOrderSubmitProperties,
                stockBucketProperties,
                waitingRoomProperties,
                new OrderTimeoutProperties()
        );

        var assessment = service.assessOrderPipelineCapacity();

        assertThat(assessment.getEntryApiRefillQps()).isEqualTo(10000D);
        assertThat(assessment.getTicketRefillQps()).isEqualTo(2000D);
        assertThat(assessment.getAsyncQueueShardCount()).isEqualTo(32);
        assertThat(assessment.getEstimatedConsumerInFlightMessages()).isEqualTo(640);
        assertThat(assessment.isFastPipelineEnabled()).isTrue();
        assertThat(assessment.isDirectRabbitEnabled()).isTrue();
        assertThat(assessment.getHardBottleneck()).contains("消费者并发");
    }

    @Test
    void planForTargetSubmitQpsReturnsPressureTuningBaseline() {
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        MqConsumerProperties mqConsumerProperties = new MqConsumerProperties();
        mqConsumerProperties.setConcurrentConsumers(8);
        mqConsumerProperties.setMaxConcurrentConsumerCap(256);
        mqConsumerProperties.setMaxAsyncQueueShardCount(128);
        AsyncOrderSubmitProperties asyncOrderSubmitProperties = new AsyncOrderSubmitProperties();
        asyncOrderSubmitProperties.setPersistRequestBeforePublish(false);
        asyncOrderSubmitProperties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_REDIS_STREAM);
        asyncOrderSubmitProperties.setMaxInFlightPerTicketCategory(100000L);
        StockBucketProperties stockBucketProperties = new StockBucketProperties();
        stockBucketProperties.setDefaultBucketCount(64);

        var service = new CapacityAssessmentService(
                rateLimitProperties,
                mqConsumerProperties,
                asyncOrderSubmitProperties,
                stockBucketProperties,
                new WaitingRoomProperties(),
                new OrderTimeoutProperties()
        );

        var plan = service.planForTargetSubmitQps(10000D);

        assertThat(plan.getTargetSubmitQps()).isEqualTo(10000D);
        assertThat(plan.getRecommendedAsyncQueueShardCount()).isGreaterThanOrEqualTo(20);
        assertThat(plan.getRecommendedMaxConcurrentConsumers()).isGreaterThanOrEqualTo(56);
        assertThat(plan.getRecommendedStockBucketCount()).isGreaterThanOrEqualTo(64);
        assertThat(plan.getHardRequirements()).isNotEmpty();
    }

    @Test
    void assessOrderPipelineCapacityTreatsRedisStreamAsEventPipeline() {
        AsyncOrderSubmitProperties asyncOrderSubmitProperties = new AsyncOrderSubmitProperties();
        asyncOrderSubmitProperties.setPersistRequestBeforePublish(false);
        asyncOrderSubmitProperties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_REDIS_STREAM);
        asyncOrderSubmitProperties.setDirectRabbitWaitForConfirm(true);
        WaitingRoomProperties waitingRoomProperties = new WaitingRoomProperties();
        waitingRoomProperties.setEnabled(true);

        var service = new CapacityAssessmentService(
                new RateLimitProperties(),
                new MqConsumerProperties(),
                asyncOrderSubmitProperties,
                new StockBucketProperties(),
                waitingRoomProperties,
                new OrderTimeoutProperties()
        );

        var assessment = service.assessOrderPipelineCapacity();

        assertThat(assessment.getHardBottleneck()).doesNotContain("Outbox");
        assertThat(assessment.getHardBottleneck()).doesNotContain("RabbitMQ");
        assertThat(assessment.getHardBottleneck()).contains("消费者并发");
    }

    @Test
    void assessOrderPipelineCapacityTreatsKafkaAsEventPipeline() {
        AsyncOrderSubmitProperties asyncOrderSubmitProperties = new AsyncOrderSubmitProperties();
        asyncOrderSubmitProperties.setPersistRequestBeforePublish(false);
        asyncOrderSubmitProperties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_KAFKA);
        WaitingRoomProperties waitingRoomProperties = new WaitingRoomProperties();
        waitingRoomProperties.setEnabled(true);

        var service = new CapacityAssessmentService(
                new RateLimitProperties(),
                new MqConsumerProperties(),
                asyncOrderSubmitProperties,
                new StockBucketProperties(),
                waitingRoomProperties,
                new OrderTimeoutProperties()
        );

        var assessment = service.assessOrderPipelineCapacity();

        assertThat(assessment.getHardBottleneck()).doesNotContain("Outbox");
        assertThat(assessment.getHardBottleneck()).contains("消费者并发");
    }
}
