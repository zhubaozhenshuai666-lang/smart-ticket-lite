package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncOrderPartitionServiceTest {

    private final AsyncOrderPartitionService service = new AsyncOrderPartitionService();

    @Test
    void routingPartitionKeyHasHighestPriority() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);
        message.setActivityScopeKey("show:1:session:1");
        message.setRoutingPartitionKey("show:1:session:1:ticket:2");

        assertThat(service.partitionKey(message)).isEqualTo("show:1:session:1:ticket:2");
        assertThat(service.partition(message, 16))
                .isEqualTo(Math.floorMod("show:1:session:1:ticket:2".hashCode(), 16));
    }

    @Test
    void activityScopeIsUsedWhenRoutingPartitionKeyIsMissing() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);
        message.setActivityScopeKey("show:1:session:1");

        assertThat(service.partitionKey(message)).isEqualTo("show:1:session:1");
    }

    @Test
    void ticketCategoryIsCompatibilityFallback() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        assertThat(service.partitionKey(message)).isEqualTo("ticket:2");
    }
}
