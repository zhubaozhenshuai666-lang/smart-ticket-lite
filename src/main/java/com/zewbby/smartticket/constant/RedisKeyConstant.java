package com.zewbby.smartticket.constant;

public final class RedisKeyConstant {

    private static final String SHOW_DETAIL_PREFIX = "show:detail:";

    private static final String SHOW_SESSIONS_PREFIX = "show:sessions:";

    private static final String SESSION_TICKET_CATEGORIES_PREFIX = "session:ticket-categories:";

    private static final String ORDER_SUBMIT_PREFIX = "order:submit:user:";

    private static final String ORDER_IDEMPOTENCY_TOKEN_PREFIX = "order:idempotency:user:";

    private static final String WAITING_ROOM_ADMISSION_PREFIX = "waiting-room:admission:";

    private static final String RATE_LIMIT_IP_PREFIX = "rate:ip:";

    private static final String RATE_LIMIT_USER_PREFIX = "rate:user:";

    private static final String RATE_LIMIT_API_PREFIX = "rate:api:";

    private static final String RATE_LIMIT_TICKET_PREFIX = "rate:ticket:";

    private static final String ORDER_RATE_LIMIT_USER_PREFIX = "rate:limit:user:";

    private static final String ORDER_RATE_LIMIT_IP_PREFIX = "rate:limit:ip:";

    private static final String ORDER_RATE_LIMIT_API_PREFIX = "rate:limit:api:";

    private static final String ORDER_RATE_LIMIT_TICKET_PREFIX = "rate:limit:ticket:";

    private static final String STOCK_TICKET_CATEGORY_PREFIX = "ticket:stock:";

    private static final String STOCK_BUCKET_COUNT_SUFFIX = ":bucket-count";

    private static final String STOCK_DEDUCTED_REQUEST_PREFIX = "ticket:stock:deducted:";

    private static final String STOCK_COMPENSATED_REQUEST_PREFIX = "ticket:stock:compensated:";

    private static final String STOCK_SOLDOUT_PREFIX = "ticket:soldout:";

    private static final String STOCK_BUCKET_PORTER_LOCK_PREFIX = "ticket:stock:porter:lock:";

    private static final String STOCK_BUCKET_PORTER_MOVE_PREFIX = "ticket:stock:porter:move:";

    private static final String AUTH_TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    private static final String AUTH_LOGIN_FAIL_PREFIX = "auth:login:fail:";

    private static final String AUTH_LOGIN_LOCK_PREFIX = "auth:login:lock:";

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

    public static String waitingRoomAdmissionTokenKey(Long ticketCategoryId, Long userId, String token) {
        return WAITING_ROOM_ADMISSION_PREFIX
                + "ticket:" + ticketCategoryId
                + ":user:" + userId
                + ":token:" + normalize(token);
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

    public static String orderRateLimitUserKey(Long userId) {
        return ORDER_RATE_LIMIT_USER_PREFIX + userId + ":order";
    }

    public static String orderRateLimitIpKey(String ip) {
        return ORDER_RATE_LIMIT_IP_PREFIX + normalize(ip) + ":order";
    }

    public static String orderRateLimitApiKey(String apiName) {
        return ORDER_RATE_LIMIT_API_PREFIX + normalize(apiName);
    }

    public static String orderRateLimitTicketKey(Long ticketCategoryId) {
        return ORDER_RATE_LIMIT_TICKET_PREFIX + ticketCategoryId;
    }

    public static String stockAvailableKey(Long ticketCategoryId) {
        return STOCK_TICKET_CATEGORY_PREFIX + ticketCategoryId;
    }

    public static String stockBucketAvailableKey(Long ticketCategoryId, Integer bucketNo) {
        return STOCK_TICKET_CATEGORY_PREFIX + ticketCategoryId + ":bucket:" + bucketNo;
    }

    public static String stockBucketAvailableKey(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo) {
        return STOCK_TICKET_CATEGORY_PREFIX + ticketCategoryId + ":v" + bucketVersion + ":bucket:" + bucketNo;
    }

    public static String stockBucketCountKey(Long ticketCategoryId) {
        return STOCK_TICKET_CATEGORY_PREFIX + ticketCategoryId + STOCK_BUCKET_COUNT_SUFFIX;
    }

    public static String stockBucketCountKey(Long ticketCategoryId, Integer bucketVersion) {
        return STOCK_TICKET_CATEGORY_PREFIX + ticketCategoryId + ":v" + bucketVersion + STOCK_BUCKET_COUNT_SUFFIX;
    }

    public static String stockRollbackRequestKey(String requestId) {
        return stockCompensatedRequestKey(requestId);
    }

    public static String stockDeductedRequestKey(String requestId) {
        return STOCK_DEDUCTED_REQUEST_PREFIX + normalize(requestId);
    }

    public static String stockCompensatedRequestKey(String requestId) {
        return STOCK_COMPENSATED_REQUEST_PREFIX + normalize(requestId);
    }

    public static String stockDeductedRecordValue(Long ticketCategoryId,
                                                  Integer bucketVersion,
                                                  Integer bucketNo,
                                                  Integer quantity) {
        return ticketCategoryId + ":v" + bucketVersion + ":" + bucketNo + ":" + quantity;
    }

    public static String stockSoldoutKey(Long ticketCategoryId) {
        return STOCK_SOLDOUT_PREFIX + ticketCategoryId;
    }

    public static String stockVersionSoldoutKey(Long ticketCategoryId, Integer bucketVersion) {
        return STOCK_SOLDOUT_PREFIX + ticketCategoryId + ":v" + bucketVersion;
    }

    public static String stockGlobalSoldoutKey(Long ticketCategoryId) {
        return STOCK_SOLDOUT_PREFIX + ticketCategoryId + ":global";
    }

    public static String stockBucketSoldoutKey(Long ticketCategoryId, Integer bucketNo) {
        return STOCK_SOLDOUT_PREFIX + ticketCategoryId + ":bucket:" + bucketNo;
    }

    public static String stockBucketSoldoutKey(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo) {
        return STOCK_SOLDOUT_PREFIX + ticketCategoryId + ":v" + bucketVersion + ":bucket:" + bucketNo;
    }

    public static String stockBucketPorterLockKey(Long ticketCategoryId, Integer fromVersion, Integer toVersion) {
        return STOCK_BUCKET_PORTER_LOCK_PREFIX + ticketCategoryId + ":v" + fromVersion + ":to:v" + toVersion;
    }

    public static String stockBucketPorterMoveKey(Long ticketCategoryId,
                                                  Integer fromVersion,
                                                  Integer fromBucketNo,
                                                  Integer toVersion,
                                                  Integer toBucketNo,
                                                  String moveId) {
        return STOCK_BUCKET_PORTER_MOVE_PREFIX
                + ticketCategoryId
                + ":v" + fromVersion
                + ":bucket:" + fromBucketNo
                + ":to:v" + toVersion
                + ":bucket:" + toBucketNo
                + ":move:" + normalize(moveId);
    }

    public static String authTokenBlacklistKey(String jti) {
        return AUTH_TOKEN_BLACKLIST_PREFIX + normalize(jti);
    }

    public static String authLoginFailKey(String loginName) {
        return AUTH_LOGIN_FAIL_PREFIX + normalize(loginName);
    }

    public static String authLoginLockKey(String loginName) {
        return AUTH_LOGIN_LOCK_PREFIX + normalize(loginName);
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
