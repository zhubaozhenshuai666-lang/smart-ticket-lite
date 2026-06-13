package com.zewbby.smartticket.domain.dto;

public record ActivityScope(String scopeKey, String routingPartitionKey) {

    public static ActivityScope from(Long showId, Long sessionId, Long ticketCategoryId) {
        String scope = "show:" + normalize(showId) + ":session:" + normalize(sessionId);
        String partition = scope + ":ticket:" + normalize(ticketCategoryId);
        return new ActivityScope(scope, partition);
    }

    private static String normalize(Long value) {
        return value == null ? "unknown" : String.valueOf(value);
    }
}
