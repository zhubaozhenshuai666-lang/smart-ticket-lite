package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.RiskControlProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RiskControlService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskControlService.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final RiskControlProperties properties;

    public RiskControlService(StringRedisTemplate stringRedisTemplate, RiskControlProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public boolean allowOrderSubmit(Long userId, String clientIp) {
        return allowOrderSubmit(userId, clientIp, null);
    }

    public boolean allowOrderSubmit(Long userId, String clientIp, String gatewayRiskDecision) {
        if (!properties.isEnabled()) {
            return true;
        }
        GatewayRiskDecision decision = resolveGatewayRiskDecision(gatewayRiskDecision);
        if (decision == GatewayRiskDecision.REJECT) {
            return false;
        }
        if (decision == GatewayRiskDecision.MISSING_REQUIRED) {
            return false;
        }
        if (decision == GatewayRiskDecision.PASS && properties.isSkipLocalCounterWhenGatewayPass()) {
            return true;
        }
        try {
            long userAttempts = increment(RedisKeyConstant.riskOrderUserKey(userId));
            if (userAttempts > properties.getMaxUserAttemptsPerMinute()) {
                return false;
            }
            long ipAttempts = increment(RedisKeyConstant.riskOrderIpKey(clientIp));
            return ipAttempts <= properties.getMaxIpAttemptsPerMinute();
        } catch (RuntimeException exception) {
            LOGGER.warn("Risk control failed and allows request, userId={}, clientIp={}, exception={}",
                    userId, clientIp, exception.getClass().getSimpleName());
            return true;
        }
    }

    private long increment(String key) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(properties.getCounterTtlSeconds()));
        }
        return value == null ? 0L : value;
    }

    private GatewayRiskDecision resolveGatewayRiskDecision(String gatewayRiskDecision) {
        if (!properties.isGatewayDecisionEnabled()) {
            return GatewayRiskDecision.IGNORE;
        }
        if (gatewayRiskDecision == null || gatewayRiskDecision.isBlank()) {
            return properties.isGatewayDecisionRequired()
                    ? GatewayRiskDecision.MISSING_REQUIRED
                    : GatewayRiskDecision.IGNORE;
        }
        String normalized = gatewayRiskDecision.trim();
        if (properties.getGatewayRejectValue().equalsIgnoreCase(normalized)) {
            return GatewayRiskDecision.REJECT;
        }
        if (properties.getGatewayPassValue().equalsIgnoreCase(normalized)) {
            return GatewayRiskDecision.PASS;
        }
        return properties.isGatewayDecisionRequired()
                ? GatewayRiskDecision.MISSING_REQUIRED
                : GatewayRiskDecision.IGNORE;
    }

    private enum GatewayRiskDecision {
        PASS,
        REJECT,
        MISSING_REQUIRED,
        IGNORE
    }
}
