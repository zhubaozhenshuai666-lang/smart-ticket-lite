package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import com.zewbby.smartticket.domain.vo.CapacityAssessmentVO;
import com.zewbby.smartticket.domain.vo.CapacityPressurePlanVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        boolean redisStream = asyncOrderSubmitProperties.isRedisStreamPublisherMode();
        boolean kafka = asyncOrderSubmitProperties.isKafkaPublisherMode();
        String bottleneck = decideHardBottleneck(fastPipeline, directRabbit, redisStream, kafka);
        String recommendation = decideRecommendation(fastPipeline, directRabbit, redisStream, kafka);
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

    public CapacityPressurePlanVO planForTargetSubmitQps(double targetSubmitQps) {
        double safeTarget = Math.max(1D, targetSubmitQps);
        int queueShards = boundedCeil(safeTarget / 500D, 1, mqConsumerProperties.getMaxAsyncQueueShardCount());
        int maxConsumers = boundedCeil(safeTarget / 180D, mqConsumerProperties.getConcurrentConsumers(), mqConsumerProperties.getMaxConcurrentConsumerCap());
        int prefetch = boundedCeil(safeTarget / Math.max(1D, maxConsumers * 80D), 1, mqConsumerProperties.getMaxPrefetchCount());
        int bucketCount = boundedCeil(safeTarget / 180D, stockBucketProperties.getDefaultBucketCount(), 512);
        long inFlightWindow = Math.max(asyncOrderSubmitProperties.getMaxInFlightPerTicketCategory(), Math.round(safeTarget * 15D));
        int admissionPerSecond = boundedCeil(safeTarget * 1.15D, 1, 1_000_000);

        List<String> requirements = new ArrayList<>();
        requirements.add("压测前必须执行 /api/admin/ops/metadata-prewarm/order-submit，确保演出关系和订单快照已进 JVM 缓存");
        requirements.add("压测必须预热 Redis 库存和库存 bucket，禁止开压后临时回源 MySQL 初始化库存");
        requirements.add("压测必须开启等待室，放行速率不要超过消费者和 MySQL 最终写入能力");
        requirements.add("压测必须独立观察 Redis Lua 耗时、MQ/Stream backlog、消费者 TPS、MySQL 行锁等待和接口 P99");
        if (!asyncOrderSubmitProperties.isDirectRabbitPublisherMode()
                && !asyncOrderSubmitProperties.isRedisStreamPublisherMode()
                && !asyncOrderSubmitProperties.isKafkaPublisherMode()) {
            requirements.add("当前仍是 Outbox 发布模式，不适合作为高峰压测主链路");
        }
        if (asyncOrderSubmitProperties.isPersistRequestBeforePublish()) {
            requirements.add("当前入口仍预写 ticket_order_request，目标 QPS 下会先卡 MySQL insert");
        }

        return new CapacityPressurePlanVO(
                safeTarget,
                safeTarget * 1.2D,
                safeTarget * 1.1D,
                safeTarget,
                queueShards,
                maxConsumers,
                prefetch,
                bucketCount,
                inFlightWindow,
                admissionPerSecond,
                requirements
        );
    }

    private String decideHardBottleneck(boolean fastPipeline, boolean directRabbit, boolean redisStream, boolean kafka) {
        if (!fastPipeline) {
            return "入口仍写 ticket_order_request，抢票洪峰首先卡在 MySQL insert 与索引维护";
        }
        if (!directRabbit && !redisStream && !kafka) {
            return "入口仍通过 Outbox 发布异步下单消息，local_message 写放大会限制吞吐";
        }
        if (directRabbit && asyncOrderSubmitProperties.isDirectRabbitWaitForConfirm()) {
            return "直发 RabbitMQ 仍等待 confirm，入口延迟受 broker confirm 抖动影响";
        }
        if (!waitingRoomProperties.isEnabled()) {
            return "缺少等待室削峰，流量洪峰会直接打到 Redis 预扣和 MQ";
        }
        return "当前瓶颈主要转移到消费者并发、MySQL 最终扣库存和订单写入";
    }

    private String decideRecommendation(boolean fastPipeline, boolean directRabbit, boolean redisStream, boolean kafka) {
        if (!fastPipeline || (!directRabbit && !redisStream && !kafka)) {
            return "高峰活动应开启 fast pipeline + kafka/direct-rabbit/redis-stream，并配合离线补偿巡检";
        }
        if (!waitingRoomProperties.isEnabled()) {
            return "大型活动必须开启等待室，用分批入场控制入口瞬时并发";
        }
        return "继续用真实压测校准消费者并发、bucket 数、in-flight 上限和 DB 写入能力";
    }

    private int boundedCeil(double value, int min, int max) {
        return Math.min(Math.max((int) Math.ceil(value), min), max);
    }
}
