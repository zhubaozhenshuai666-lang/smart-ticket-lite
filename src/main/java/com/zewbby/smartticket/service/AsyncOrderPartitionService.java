package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.springframework.stereotype.Service;

@Service
public class AsyncOrderPartitionService {

    public int partition(AsyncCreateOrderMessage message, int partitionCount) {
        if (partitionCount <= 1) {
            return 0;
        }
        String key = partitionKey(message);
        return Math.floorMod(key.hashCode(), partitionCount);
    }

    public String partitionKey(AsyncCreateOrderMessage message) {
        if (message == null) {
            return "unknown";
        }
        if (message.getRoutingPartitionKey() != null && !message.getRoutingPartitionKey().isBlank()) {
            return message.getRoutingPartitionKey().trim();
        }
        if (message.getTicketCategoryId() != null && message.getStockBucketNo() != null) {
            return "ticket:" + message.getTicketCategoryId()
                    + ":v" + normalizeBucketVersion(message.getStockBucketVersion())
                    + ":bucket:" + message.getStockBucketNo();
        }
        if (message.getActivityScopeKey() != null && !message.getActivityScopeKey().isBlank()) {
            return message.getActivityScopeKey().trim();
        }
        if (message.getTicketCategoryId() != null) {
            return "ticket:" + message.getTicketCategoryId();
        }
        return "request:" + (message.getRequestId() == null ? "unknown" : message.getRequestId());
    }

    private String normalizeBucketVersion(Integer bucketVersion) {
        return bucketVersion == null || bucketVersion <= 0 ? "unknown" : String.valueOf(bucketVersion);
    }
}
