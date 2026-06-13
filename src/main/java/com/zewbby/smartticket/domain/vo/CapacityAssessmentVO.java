package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityAssessmentVO {

    private String profile;

    private double entryApiRefillQps;

    private double activityRefillQps;

    private double ticketRefillQps;

    private int asyncQueueShardCount;

    private int maxConcurrentConsumers;

    private int prefetchCount;

    private int estimatedConsumerInFlightMessages;

    private long maxInFlightPerTicketCategory;

    private int stockBucketCount;

    private int activeBucketProbeCount;

    private boolean waitingRoomEnabled;

    private boolean fastPipelineEnabled;

    private boolean directRabbitEnabled;

    private boolean directRabbitWaitForConfirm;

    private boolean perOrderTimeoutDelayMessageEnabled;

    private String hardBottleneck;

    private String recommendation;
}
