package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityPressurePlanVO {

    private double targetSubmitQps;

    private double recommendedApiRefillQps;

    private double recommendedActivityRefillQps;

    private double recommendedTicketRefillQps;

    private int recommendedAsyncQueueShardCount;

    private int recommendedMaxConcurrentConsumers;

    private int recommendedPrefetchCount;

    private int recommendedStockBucketCount;

    private long recommendedMaxInFlightPerActivityTicketCategory;

    private int recommendedWaitingRoomAdmissionPerSecond;

    private List<String> hardRequirements;
}
