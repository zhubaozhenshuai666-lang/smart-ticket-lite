package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.domain.vo.OpsMetricsSummaryVO;
import com.zewbby.smartticket.mapper.DeadLetterMessageMapper;
import com.zewbby.smartticket.mapper.LocalMessageMapper;
import com.zewbby.smartticket.mapper.StockCompensationRecordMapper;
import com.zewbby.smartticket.mapper.StockConsistencyRecordMapper;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class ObservabilityMetricsServiceImpl implements ObservabilityMetricsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservabilityMetricsServiceImpl.class);

    private final LocalMessageMapper localMessageMapper;

    private final DeadLetterMessageMapper deadLetterMessageMapper;

    private final StockConsistencyRecordMapper stockConsistencyRecordMapper;

    private final StockCompensationRecordMapper stockCompensationRecordMapper;

    private final Counter orderCreatedCounter;

    private final Counter orderPaidCounter;

    private final Counter orderCancelledCounter;

    private final Counter asyncOrderRequestSuccessCounter;

    private final Counter asyncOrderRequestFailedCounter;

    private final Counter rateLimitRejectedCounter;

    private final Counter soldoutFastfailCounter;

    private final Counter stockBucketPorterMovedCounter;

    private final Counter stockBucketPorterLockSkippedCounter;

    private final Counter stockBucketPorterFailedCounter;

    public ObservabilityMetricsServiceImpl(MeterRegistry meterRegistry,
                                           LocalMessageMapper localMessageMapper,
                                           DeadLetterMessageMapper deadLetterMessageMapper,
                                           StockConsistencyRecordMapper stockConsistencyRecordMapper,
                                           StockCompensationRecordMapper stockCompensationRecordMapper) {
        this.localMessageMapper = localMessageMapper;
        this.deadLetterMessageMapper = deadLetterMessageMapper;
        this.stockConsistencyRecordMapper = stockConsistencyRecordMapper;
        this.stockCompensationRecordMapper = stockCompensationRecordMapper;
        this.orderCreatedCounter = counter(meterRegistry, "order.created.count", "正式订单创建次数");
        this.orderPaidCounter = counter(meterRegistry, "order.paid.count", "订单支付成功次数");
        this.orderCancelledCounter = counter(meterRegistry, "order.cancelled.count", "用户主动取消订单次数");
        this.asyncOrderRequestSuccessCounter = counter(meterRegistry, "async.order.request.success.count", "异步下单请求成功次数");
        this.asyncOrderRequestFailedCounter = counter(meterRegistry, "async.order.request.failed.count", "异步下单请求失败次数");
        this.rateLimitRejectedCounter = counter(meterRegistry, "rate.limit.rejected.count", "限流拒绝次数");
        this.soldoutFastfailCounter = counter(meterRegistry, "soldout.fastfail.count", "售罄快速失败次数");
        this.stockBucketPorterMovedCounter = counter(meterRegistry, "stock.bucket.porter.moved.quantity", "Porter搬运库存张数");
        this.stockBucketPorterLockSkippedCounter = counter(meterRegistry, "stock.bucket.porter.lock.skipped.count", "Porter未抢到锁跳过次数");
        this.stockBucketPorterFailedCounter = counter(meterRegistry, "stock.bucket.porter.failed.count", "Porter搬运失败次数");
        registerGauges(meterRegistry);
    }

    /**
     * 注册交易系统需要持续观察的存量风险指标。
     *
     * 接口日志只能告诉你“某次请求发生了什么”，但不能告诉你系统里还剩多少未处理风险。
     * local_message DEAD、dead_letter PENDING、库存差异 PENDING、补偿 FAILED 都是会影响交易闭环的存量问题，
     * 必须做成 Gauge，让运维能看到当前还有多少坑没有处理。
     */
    private void registerGauges(MeterRegistry meterRegistry) {
        Gauge.builder("local.message.failed.count", this, service -> service.localMessageFailedCount())
                .description("本地消息发送失败但尚未死亡的当前数量")
                .register(meterRegistry);
        Gauge.builder("local.message.dead.count", this, service -> service.localMessageDeadCount())
                .description("本地消息进入 DEAD 后不再自动重试的当前数量")
                .register(meterRegistry);
        Gauge.builder("dead.letter.pending.count", this, service -> service.deadLetterPendingCount())
                .description("消费端死信待人工处理的当前数量")
                .register(meterRegistry);
        Gauge.builder("stock.consistency.pending.count", this, service -> service.stockConsistencyPendingCount())
                .description("库存一致性差异待处理的当前数量")
                .register(meterRegistry);
        Gauge.builder("stock.compensation.failed.count", this, service -> service.stockCompensationFailedCount())
                .description("库存补偿失败的当前数量")
                .register(meterRegistry);
    }

    @Override
    public void recordOrderCreated() {
        orderCreatedCounter.increment();
    }

    @Override
    public void recordOrderPaid() {
        orderPaidCounter.increment();
    }

    @Override
    public void recordOrderCancelled() {
        orderCancelledCounter.increment();
    }

    @Override
    public void recordAsyncOrderRequestSuccess() {
        asyncOrderRequestSuccessCounter.increment();
    }

    @Override
    public void recordAsyncOrderRequestFailed() {
        asyncOrderRequestFailedCounter.increment();
    }

    @Override
    public void recordRateLimitRejected() {
        rateLimitRejectedCounter.increment();
    }

    @Override
    public void recordSoldoutFastFail() {
        soldoutFastfailCounter.increment();
    }

    @Override
    public void recordStockBucketPorterMoved(int movedQuantity) {
        if (movedQuantity > 0) {
            stockBucketPorterMovedCounter.increment(movedQuantity);
        }
    }

    @Override
    public void recordStockBucketPorterLockSkipped() {
        stockBucketPorterLockSkippedCounter.increment();
    }

    @Override
    public void recordStockBucketPorterFailed() {
        stockBucketPorterFailedCounter.increment();
    }

    /**
     * 后台 summary 是给排障人员快速看当前风险面的。
     *
     * TraceId 适合追一条请求从入口到 MQ、数据库的调用链；指标适合回答“系统整体是否健康”。
     * 两者不是替代关系：TraceId 查单点，Metrics 看趋势和积压。
     */
    @Override
    public OpsMetricsSummaryVO getSummary() {
        return new OpsMetricsSummaryVO(
                orderCreatedCounter.count(),
                orderPaidCounter.count(),
                orderCancelledCounter.count(),
                asyncOrderRequestSuccessCounter.count(),
                asyncOrderRequestFailedCounter.count(),
                localMessageFailedCount(),
                localMessageDeadCount(),
                deadLetterPendingCount(),
                stockConsistencyPendingCount(),
                stockCompensationFailedCount(),
                rateLimitRejectedCounter.count(),
                soldoutFastfailCounter.count()
        );
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private double localMessageFailedCount() {
        return safeCount(() -> localMessageMapper.countByStatus("FAILED"), "local.message.failed.count");
    }

    private double localMessageDeadCount() {
        return safeCount(() -> localMessageMapper.countByStatus("DEAD"), "local.message.dead.count");
    }

    private double deadLetterPendingCount() {
        return safeCount(() -> deadLetterMessageMapper.countByStatus("PENDING"), "dead.letter.pending.count");
    }

    private double stockConsistencyPendingCount() {
        return safeCount(() -> stockConsistencyRecordMapper.countByStatus("PENDING"), "stock.consistency.pending.count");
    }

    private double stockCompensationFailedCount() {
        return safeCount(() -> stockCompensationRecordMapper.countByStatus("FAILED"), "stock.compensation.failed.count");
    }

    private double safeCount(Supplier<Long> supplier, String metricName) {
        try {
            Long value = supplier.get();
            return value == null ? 0 : value;
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to collect metric {}", metricName, exception);
            return 0;
        }
    }
}
