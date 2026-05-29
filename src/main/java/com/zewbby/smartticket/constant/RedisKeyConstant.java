package com.zewbby.smartticket.constant;

public final class RedisKeyConstant {

    private static final String SHOW_DETAIL_PREFIX = "show:detail:";

    private static final String SHOW_SESSIONS_PREFIX = "show:sessions:";

    private static final String SESSION_TICKET_CATEGORIES_PREFIX = "session:ticket-categories:";

    private static final String ORDER_SUBMIT_PREFIX = "order:submit:user:";

    private static final String ORDER_IDEMPOTENCY_TOKEN_PREFIX = "order:idempotency:user:";

    private static final String RATE_LIMIT_IP_PREFIX = "rate:ip:";

    private static final String RATE_LIMIT_USER_PREFIX = "rate:user:";

    private static final String RATE_LIMIT_API_PREFIX = "rate:api:";

    private static final String RATE_LIMIT_TICKET_PREFIX = "rate:ticket:";

    private RedisKeyConstant() {
    }

    public static String showDetailKey(Long showId) {
        return SHOW_DETAIL_PREFIX + showId;
    }

    public static String showSessionsKey(Long showId) {
        return SHOW_SESSIONS_PREFIX + showId;
    }

    public static String sessionTicketCategoriesKey(Long sessionId) {
        return SESSION_TICKET_CATEGORIES_PREFIX + sessionId;
    }

    public static String orderSubmitKey(Long userId, Long ticketCategoryId) {
        return ORDER_SUBMIT_PREFIX + userId + ":ticket:" + ticketCategoryId;
    }

    public static String orderIdempotencyTokenKey(Long userId, String token) {
        return ORDER_IDEMPOTENCY_TOKEN_PREFIX + userId + ":token:" + normalize(token);
    }

    public static String rateLimitIpKey(String ip, String uri) {
        return RATE_LIMIT_IP_PREFIX + normalize(ip) + ":" + normalize(uri);
    }

    public static String rateLimitUserKey(Long userId, String action) {
        return RATE_LIMIT_USER_PREFIX + userId + ":" + normalize(action);
    }

    public static String rateLimitApiKey(String uri) {
        return RATE_LIMIT_API_PREFIX + normalize(uri);
    }

    public static String rateLimitTicketKey(Long ticketCategoryId, String action) {
        return RATE_LIMIT_TICKET_PREFIX + ticketCategoryId + ":" + normalize(action);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim()
                .replaceAll("^/+", "")
                .replaceAll("\\s+", "_")
                .replace("/", ":");
    }
}
