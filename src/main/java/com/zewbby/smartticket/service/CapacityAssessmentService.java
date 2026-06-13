package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import com.zewbby.smartticket.domain.vo.CapacityAssessmentVO;
import org.springframework.stereotype.Service;

@Service
public class CapacityAssessmentService {

    private final RateLimitProperties rateLimitProperties;

    private final MqConsumerProperties mqConsumerProperties;

    private final AsyncOrderSubmitProperties asyncOrderSubmitProperties;

    private final StockBucketProperties stockBucketProperties;

    private final WaitingRoomProperties waitingRoomProperties;

    private final OrderTimeoutProperties orderTimeoutProperties;

    public CapacityAssessmentService(RateLimitProperties rateLimitProperties,
                                     MqConsumerProperties mqConsumerProperties,
                                     AsyncOrderSubmitProperties asyncOrderSubmitProperties,
                                     StockBucketProperties stockBucketProperties,
                                     WaitingRoomProperties waitingRoomProperties,
                                     OrderTimeoutProperties orderTimeoutProperties) {
        this.rateLimitProperties = rateLimitProperties;
        this.mqConsumerProperties = mqConsumerProperties;
        this.asyncOrderSubmitProperties = asyncOrderSubmitProperties;
        this.stockBucketProperties = stockBucketProperties;
        this.waitingRoomProperties = waitingRoomProperties;
        this.orderTimeoutProperties = orderTimeoutProperties;
    }

    public CapacityAssessmentVO assessOrderPipelineCapacity() {
        int maxConsumers = mqConsumerProperties.getMaxConcurrentConsumers();
        int prefetch = mqConsumerProperties.getPrefetchCount();
        boolean fastPipeline = !asyncOrderSubmitProperties.isPersistRequestBeforePublish();
        boolean directRabbit = asyncOrderSubmitProperties.isDirectRabbitPublisherMode();
        String bottleneck = decideHardBottleneck(fastPipeline, directRabbit);
        String recommendation = decideRecommendation(fastPipeline, directRabbit);
        return new CapacityAssessmentVO(
                "order-submit-pipeline",
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                rateLimitProperties.getOrderTicketRefillRatePerSecond(),
                mqConsumerProperties.getAsyncQueueShardCount(),
                maxConsumers,
                prefetch,
                maxConsumers * prefetch,
                asyncOrderSubmitProperties.getMaxInFlightPerTicketCategory(),
                stockBucketProperties.getDefaultBucketCount(),
                stockBucketProperties.getActiveProbeCount(),
                waitingRoomProperties.isEnabled(),
                fastPipeline,
                directRabbit,
                asyncOrderSubmitProperties.isDirectRabbitWaitForConfirm(),
                orderTimeoutProperties.isDelayMessageEnabled(),
                bottleneck,
                recommendation
        );
    }

    private String decideHardBottleneck(boolean fastPipeline, boolean directRabbit) {
        if (!fastPipeline) {
            return "入口仍写 ticket_order_request，抢票洪峰首先卡在 MySQL insert 与索引维护";
        }
        if (!directRabbit) {
            return "入口仍通过 Outbox 发布异步下单消息，local_message 写放大会限制吞吐";
        }
        if (asyncOrderSubmitProperties.isDirectRabbitWaitForConfirm()) {
            return "直发 RabbitMQ 仍等待 confirm，入口延迟受 broker confirm 抖动影响";
        }
        if (!waitingRoomProperties.isEnabled()) {
            return "缺少等待室削峰，流量洪峰会直接打到 Redis 预扣和 MQ";
        }
        return "当前瓶颈主要转移到消费者并发、MySQL 最终扣库存和订单写入";
    }

    private String decideRecommendation(boolean fastPipeline, boolean directRabbit) {
        if (!fastPipeline || !directRabbit) {
            return "高峰活动应开启 fast pipeline + direct-rabbit，并配合离线补偿巡检";
        }
        if (!waitingRoomProperties.isEnabled()) {
            return "大型活动必须开启等待室，用分批入场控制入口瞬时并发";
        }
        return "继续用真实压测校准消费者并发、bucket 数、in-flight 上限和 DB 写入能力";
    }
}
