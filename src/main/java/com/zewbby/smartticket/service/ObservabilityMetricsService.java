package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.vo.OpsMetricsSummaryVO;

public interface ObservabilityMetricsService {

    void recordOrderCreated();

    void recordOrderPaid();

    void recordOrderCancelled();

    void recordAsyncOrderRequestSuccess();

    void recordAsyncOrderRequestFailed();

    void recordRateLimitRejected();

    void recordSoldoutFastFail();

    void recordStockBucketPorterMoved(int movedQuantity);

    void recordStockBucketPorterLockSkipped();

    void recordStockBucketPorterFailed();

    OpsMetricsSummaryVO getSummary();
}
