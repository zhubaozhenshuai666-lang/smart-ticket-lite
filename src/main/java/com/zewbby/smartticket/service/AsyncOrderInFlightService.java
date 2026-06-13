package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class AsyncOrderInFlightService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncOrderInFlightService.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final AsyncOrderSubmitProperties properties;

    private final DefaultRedisScript<Long> acquireScript;

    private final DefaultRedisScript<Long> releaseScript;

    public AsyncOrderInFlightService(StringRedisTemplate stringRedisTemplate,
                                     AsyncOrderSubmitProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.acquireScript = buildScript("lua/async_order_inflight_acquire.lua");
        this.releaseScript = buildScript("lua/async_order_inflight_release.lua");
    }

    public boolean tryAcquire(Long ticketCategoryId) {
        if (!properties.isInFlightControlEnabled()) {
            return true;
        }
        return tryAcquireKey(
                RedisKeyConstant.asyncOrderInFlightKey(ticketCategoryId),
                properties.getMaxInFlightPerTicketCategory(),
                ticketCategoryId
        );
    }

    public boolean tryAcquire(String activityScopeKey, Long ticketCategoryId, long maxInFlight) {
        if (!properties.isInFlightControlEnabled()) {
            return true;
        }
        return tryAcquireKey(
                RedisKeyConstant.asyncOrderActivityInFlightKey(activityScopeKey, ticketCategoryId),
                maxInFlight,
                ticketCategoryId
        );
    }

    private boolean tryAcquireKey(String key, long maxInFlight, Long ticketCategoryId) {
        try {
            Long result = stringRedisTemplate.execute(
                    acquireScript,
                    Collections.singletonList(key),
                    String.valueOf(Math.max(1L, maxInFlight)),
                    String.valueOf(properties.getInFlightCounterTtlSeconds())
            );
            return result != null && result > 0L;
        } catch (RuntimeException exception) {
            LOGGER.warn("Async order in-flight acquire failed and rejects request, ticketCategoryId={}",
                    ticketCategoryId, exception);
            return false;
        }
    }

    public void release(Long ticketCategoryId) {
        //校验是否启动了In-Flight
        if (!properties.isInFlightControlEnabled() || ticketCategoryId == null) {
            return;
        }
        releaseKey(RedisKeyConstant.asyncOrderInFlightKey(ticketCategoryId), ticketCategoryId);
    }

    public void release(String activityScopeKey, Long ticketCategoryId) {
        if (!properties.isInFlightControlEnabled() || ticketCategoryId == null) {
            return;
        }
        releaseKey(RedisKeyConstant.asyncOrderActivityInFlightKey(activityScopeKey, ticketCategoryId), ticketCategoryId);
    }

    private void releaseKey(String key, Long ticketCategoryId) {
        try {
            //执行释放脚本
            stringRedisTemplate.execute(releaseScript, Collections.singletonList(key));
        } catch (RuntimeException exception) {
            LOGGER.warn("Async order in-flight release failed, ticketCategoryId={}", ticketCategoryId, exception);
        }
    }

    private DefaultRedisScript<Long> buildScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
