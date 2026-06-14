package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.mapper.DeadLetterMessageMapper;
import com.zewbby.smartticket.mapper.LocalMessageMapper;
import com.zewbby.smartticket.mapper.StockCompensationRecordMapper;
import com.zewbby.smartticket.mapper.StockConsistencyRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObservabilityMetricsServiceImplTest {

    @Mock
    private LocalMessageMapper localMessageMapper;

    @Mock
    private DeadLetterMessageMapper deadLetterMessageMapper;

    @Mock
    private StockConsistencyRecordMapper stockConsistencyRecordMapper;

    @Mock
    private StockCompensationRecordMapper stockCompensationRecordMapper;

    @Test
    void countersAndGaugesAreRegisteredAndSummaryCanBeQueried() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(localMessageMapper.countByStatus("FAILED")).thenReturn(2L);
        when(localMessageMapper.countByStatus("DEAD")).thenReturn(1L);
        when(deadLetterMessageMapper.countByStatus("PENDING")).thenReturn(3L);
        when(stockConsistencyRecordMapper.countByStatus("PENDING")).thenReturn(4L);
        when(stockCompensationRecordMapper.countByStatus("FAILED")).thenReturn(5L);

        ObservabilityMetricsServiceImpl service = new ObservabilityMetricsServiceImpl(
                meterRegistry,
                localMessageMapper,
                deadLetterMessageMapper,
                stockConsistencyRecordMapper,
                stockCompensationRecordMapper
        );

        service.recordOrderCreated();
        service.recordOrderPaid();
        service.recordOrderCancelled();
        service.recordAsyncOrderRequestSuccess();
        service.recordAsyncOrderRequestFailed();
        service.recordRateLimitRejected();
        service.recordSoldoutFastFail();

        assertThat(meterRegistry.counter("order.created.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("order.paid.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("order.cancelled.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("async.order.request.success.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("async.order.request.failed.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("rate.limit.rejected.count").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("soldout.fastfail.count").count()).isEqualTo(1);
        assertThat(meterRegistry.find("local.message.failed.count").gauge().value()).isEqualTo(2);
        assertThat(meterRegistry.find("local.message.dead.count").gauge().value()).isEqualTo(1);
        assertThat(meterRegistry.find("dead.letter.pending.count").gauge().value()).isEqualTo(3);
        assertThat(meterRegistry.find("stock.consistency.pending.count").gauge().value()).isEqualTo(4);
        assertThat(meterRegistry.find("stock.compensation.failed.count").gauge().value()).isEqualTo(5);

        var summary = service.getSummary();
        assertThat(summary.getOrderCreatedCount()).isEqualTo(1);
        assertThat(summary.getOrderPaidCount()).isEqualTo(1);
        assertThat(summary.getOrderCancelledCount()).isEqualTo(1);
        assertThat(summary.getAsyncOrderRequestSuccessCount()).isEqualTo(1);
        assertThat(summary.getAsyncOrderRequestFailedCount()).isEqualTo(1);
        assertThat(summary.getLocalMessageFailedCount()).isEqualTo(2);
        assertThat(summary.getDeadLetterPendingCount()).isEqualTo(3);
        assertThat(summary.getStockConsistencyPendingCount()).isEqualTo(4);
        assertThat(summary.getStockCompensationFailedCount()).isEqualTo(5);
        assertThat(summary.getRateLimitRejectedCount()).isEqualTo(1);
        assertThat(summary.getSoldoutFastfailCount()).isEqualTo(1);
    }
}
