package com.zewbby.smartticket.ratelimit;

import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.enums.LocalMessageStatusEnum;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class RateLimitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitService.class);

    private static final int REQUESTED_TOKENS_PER_ORDER = 1;

    private final StringRedisTemplate stringRedisTemplate;

    private final RateLimitProperties rateLimitProperties;

    private final DefaultRedisScript<Long> tokenBucketScript;

    private final DefaultRedisScript<Long> multiTokenBucketsScript;

    private final ObservabilityMetricsService observabilityMetricsService;

    private final LocalMessageService localMessageService;

    private volatile long lastBackpressureCheckAt;

    private volatile boolean lastBackpressureRejected;

    private volatile long lastLocalMessageBacklog;

    @Autowired
    public RateLimitService(StringRedisTemplate stringRedisTemplate,
	                            RateLimitProperties rateLimitProperties,
	                            ObservabilityMetricsService observabilityMetricsService,
                                LocalMessageService localMessageService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitProperties = rateLimitProperties;
        this.observabilityMetricsService = observabilityMetricsService;
        this.localMessageService = localMessageService;
        this.tokenBucketScript = buildScript("lua/rate_limit_token_bucket.lua");
        this.multiTokenBucketsScript = buildScript("lua/rate_limit_multi_token_buckets.lua");
    }

    public RateLimitService(StringRedisTemplate stringRedisTemplate,
                            RateLimitProperties rateLimitProperties,
                            ObservabilityMetricsService observabilityMetricsService) {
        this(stringRedisTemplate, rateLimitProperties, observabilityMetricsService, null);
    }

    /**
     * 兼容旧固定窗口调用，内部已经改成令牌桶。
     *
     * 固定窗口的问题是窗口边界会有突刺，比如第 10 秒末尾放过一批请求，第 11 秒窗口重置又放过一批请求。
     * 这里保留旧签名是为了不让历史代码和测试一次性全部迁移，但算法语义已经变成“容量=limit，
     * 补充速率=limit/windowSeconds”的令牌桶。
     */
    public boolean tryAcquire(String key, int limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("limit and windowSeconds must be positive");
        }
        return tryAcquireTokenBucket(
                key,
                limit,
                (double) limit / windowSeconds,
                REQUESTED_TOKENS_PER_ORDER,
                Math.max(windowSeconds * 2, rateLimitProperties.getKeyTtlSeconds())
        );
    }

    /**
     * 使用 Redis Lua 令牌桶尝试消耗 token。
     *
     * 令牌桶只保存当前 tokens 和上次补充时间，不会像高频滑动窗口 ZSET 那样为每个请求保存一条时间戳。
     * Java 侧只负责传配置；真正的“补充 token、判断、扣减、写回”全部在 Lua 内部原子完成，
     * 避免多个并发请求读到同一个旧 tokens 后一起放行。
     *
     * @return true 表示本维度放行；false 表示 token 不足或 Redis 限流组件不可用。
     */
    public boolean tryAcquireTokenBucket(String key,
                                         int capacity,
                                         double refillRatePerSecond,
                                         int requestedTokens,
                                         long keyTtlSeconds) {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }
        if (capacity <= 0 || refillRatePerSecond <= 0 || requestedTokens <= 0 || keyTtlSeconds <= 0) {
            throw new IllegalArgumentException("token bucket arguments must be positive");
        }
        try {
            Long result = stringRedisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerSecond),
                    String.valueOf(requestedTokens),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(keyTtlSeconds)
            );
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                observabilityMetricsService.recordRateLimitRejected();
            }
            return allowed;
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis token bucket rate limit failed and rejects request, key={}", key, exception);
            observabilityMetricsService.recordRateLimitRejected();
            return false;
        }
    }

    /**
     * 下单入口的多维限流组合。
     *
     * 用户维度用于防止单个账号刷下单；IP 维度用于挡住单来源洪峰；API 维度保护整体入口；
     * 票档维度保护热点票档。四个维度任意一个拒绝，就说明继续放行会把压力打到 Redis 预扣、MQ 或 MySQL，
     * 因此入口直接失败。
     */
    public boolean tryAcquireOrderSubmit(Long userId,
                                         String clientIp,
                                         String apiName,
                                         Long ticketCategoryId,
                                         boolean includeTicketDimension) {
        if (isBackpressureRejected()) {
            observabilityMetricsService.recordRateLimitRejected();
            return false;
        }

        List<String> keys = new ArrayList<>();
        List<TokenBucketSpec> buckets = new ArrayList<>();
        keys.add(RedisKeyConstant.orderRateLimitUserKey(userId));
        buckets.add(new TokenBucketSpec(
                rateLimitProperties.getOrderUserCapacity(),
                rateLimitProperties.getOrderUserRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER
        ));
        keys.add(RedisKeyConstant.orderRateLimitIpKey(clientIp));
        buckets.add(new TokenBucketSpec(
                rateLimitProperties.getOrderIpCapacity(),
                rateLimitProperties.getOrderIpRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER
        ));
        keys.add(RedisKeyConstant.orderRateLimitApiKey(apiName));
        buckets.add(new TokenBucketSpec(
                rateLimitProperties.getOrderApiCapacity(),
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER
        ));
        if (includeTicketDimension) {
            keys.add(RedisKeyConstant.orderRateLimitTicketKey(ticketCategoryId));
            buckets.add(new TokenBucketSpec(
                    rateLimitProperties.getOrderTicketCapacity(),
                    rateLimitProperties.getOrderTicketRefillRatePerSecond(),
                    REQUESTED_TOKENS_PER_ORDER
            ));
        }
        return tryAcquireMultiTokenBuckets(keys, buckets, rateLimitProperties.getKeyTtlSeconds());
    }

    //对票的key限流
    public boolean tryAcquireOrderTicket(Long ticketCategoryId) {
        return tryAcquireTokenBucket(
                RedisKeyConstant.orderRateLimitTicketKey(ticketCategoryId),
                rateLimitProperties.getOrderTicketCapacity(),
                rateLimitProperties.getOrderTicketRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER,
                rateLimitProperties.getKeyTtlSeconds()
        );
    }

    public boolean tryAcquireOrderActivityAndTicket(String activityScopeKey, Long ticketCategoryId) {
        return tryAcquireTwoTokenBuckets(
                RedisKeyConstant.orderRateLimitActivityKey(activityScopeKey),
                rateLimitProperties.getOrderApiCapacity(),
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER,
                RedisKeyConstant.orderRateLimitTicketKey(ticketCategoryId),
                rateLimitProperties.getOrderTicketCapacity(),
                rateLimitProperties.getOrderTicketRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER,
                rateLimitProperties.getKeyTtlSeconds()
        );
    }

    public boolean tryAcquireOrderActivity(String activityScopeKey) {
        return tryAcquireTokenBucket(
                RedisKeyConstant.orderRateLimitActivityKey(activityScopeKey),
                rateLimitProperties.getOrderApiCapacity(),
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                REQUESTED_TOKENS_PER_ORDER,
                rateLimitProperties.getKeyTtlSeconds()
        );
    }

    private boolean tryAcquireTwoTokenBuckets(String firstKey,
                                              int firstCapacity,
                                              double firstRefillRatePerSecond,
                                              int firstRequestedTokens,
                                              String secondKey,
                                              int secondCapacity,
                                              double secondRefillRatePerSecond,
                                              int secondRequestedTokens,
                                              long keyTtlSeconds) {
        return tryAcquireMultiTokenBuckets(
                List.of(firstKey, secondKey),
                List.of(
                        new TokenBucketSpec(firstCapacity, firstRefillRatePerSecond, firstRequestedTokens),
                        new TokenBucketSpec(secondCapacity, secondRefillRatePerSecond, secondRequestedTokens)
                ),
                keyTtlSeconds
        );
    }

    private boolean tryAcquireMultiTokenBuckets(List<String> keys,
                                                List<TokenBucketSpec> buckets,
                                                long keyTtlSeconds) {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }
        if (keys == null || buckets == null || keys.isEmpty() || keys.size() != buckets.size() || keyTtlSeconds <= 0) {
            throw new IllegalArgumentException("multi token bucket arguments must be positive");
        }
        List<String> args = new ArrayList<>(3 + buckets.size() * 3);
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add(String.valueOf(keyTtlSeconds));
        args.add(String.valueOf(buckets.size()));
        for (TokenBucketSpec bucket : buckets) {
            if (bucket.capacity() <= 0 || bucket.refillRatePerSecond() <= 0 || bucket.requestedTokens() <= 0) {
                throw new IllegalArgumentException("multi token bucket arguments must be positive");
            }
            args.add(String.valueOf(bucket.capacity()));
            args.add(String.valueOf(bucket.refillRatePerSecond()));
            args.add(String.valueOf(bucket.requestedTokens()));
        }
        try {
            Long result = stringRedisTemplate.execute(
                    multiTokenBucketsScript,
                    keys,
                    args.toArray(new String[0])
            );
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                observabilityMetricsService.recordRateLimitRejected();
            }
            return allowed;
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis multi-token-bucket rate limit failed and rejects request, keys={}", keys, exception);
            observabilityMetricsService.recordRateLimitRejected();
            return false;
        }
    }

    public long getSoldoutTtlSeconds() {
        return rateLimitProperties.getSoldoutTtlSeconds();
    }

    private boolean isBackpressureRejected() {
        if (!rateLimitProperties.isBackpressureEnabled() || localMessageService == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackpressureCheckAt < rateLimitProperties.getBackpressureSampleIntervalMillis()) {
            return lastBackpressureRejected;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - lastBackpressureCheckAt < rateLimitProperties.getBackpressureSampleIntervalMillis()) {
                return lastBackpressureRejected;
            }
            try {
                long backlog = localMessageService.countByStatus(LocalMessageStatusEnum.INIT.getCode())
                        + localMessageService.countByStatus(LocalMessageStatusEnum.FAILED.getCode())
                        + localMessageService.countByStatus(LocalMessageStatusEnum.SENDING.getCode())
                        + localMessageService.countByStatus(LocalMessageStatusEnum.SENT.getCode());
                lastLocalMessageBacklog = backlog;
                lastBackpressureRejected = backlog >= rateLimitProperties.getLocalMessageBacklogRejectThreshold();
                if (lastBackpressureRejected) {
                    LOGGER.warn("Order submit backpressure rejected by local message backlog, backlog={}, threshold={}",
                            backlog, rateLimitProperties.getLocalMessageBacklogRejectThreshold());
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to sample local message backlog for backpressure, lastBacklog={}",
                        lastLocalMessageBacklog, exception);
                lastBackpressureRejected = false;
            } finally {
                lastBackpressureCheckAt = now;
            }
            return lastBackpressureRejected;
        }
    }

    //创建script脚本文件
    private DefaultRedisScript<Long> buildScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    private record TokenBucketSpec(int capacity, double refillRatePerSecond, int requestedTokens) {
    }
}
