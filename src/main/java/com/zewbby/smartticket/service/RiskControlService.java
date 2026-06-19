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
        //提供一键关闭风控的动态降级能力
        /*架构意义：
        在极端抢票高峰期，如果整个系统的 CPU 或网络 I/O 濒临崩溃，
        或者风控依赖的底层组件发生大规模故障，可通过配置中心热更新此配置。
        直接跳过所有风控判断（放行全部流量），牺牲防刷能力来保全核心交易链路的可用性。
         */
        if (!properties.isEnabled()) {
            return true;
        }

        //网关风控层
        //依托更前端的 API 网关或 WAF（Web 应用防火墙）传递过来的安全判定指纹进行快速拦截。
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


        //本地 Redis 频控层
        //基于固定时间窗口的应用层限流，防止单台设备或单账号瞬时发起海量请求。
        try {
            long userAttempts = increment(RedisKeyConstant.riskOrderUserKey(userId));
            if (userAttempts > properties.getMaxUserAttemptsPerMinute()) {
                return false;
            }
            long ipAttempts = increment(RedisKeyConstant.riskOrderIpKey(clientIp));
            return ipAttempts <= properties.getMaxIpAttemptsPerMinute();
        }
        //高可用容灾机制
        //捕获 try 块内所有与 Redis 交互可能产生的运行时异常（如连接池耗尽、读写超时）。
        /*
        遵循了异常放行（Fail-Open）的高可用设计原则。
        风控是一个旁路系统，它的局部故障绝对不能阻塞主交易流程。
        当基础设施出现抖动时，系统宁可放过一部分黄牛，也必须保障正常用户依然能够完成下单操作。
         */
        catch (RuntimeException exception) {
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
