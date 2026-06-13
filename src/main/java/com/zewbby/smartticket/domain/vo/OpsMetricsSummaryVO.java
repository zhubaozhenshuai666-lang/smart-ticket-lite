package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpsMetricsSummaryVO {

    private double orderCreatedCount;

    private double orderPaidCount;

    private double orderCancelledCount;

    private double asyncOrderRequestSuccessCount;

    private double asyncOrderRequestFailedCount;

    private double localMessageFailedCount;

    private double localMessageDeadCount;

    private double deadLetterPendingCount;

    private double stockConsistencyPendingCount;

    private double stockCompensationFailedCount;

    private double rateLimitRejectedCount;

    private double soldoutFastfailCount;
}
