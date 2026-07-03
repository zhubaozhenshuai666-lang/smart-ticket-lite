package com.zewbby.smartticket.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class MqConsumeTraceAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqConsumeTraceAspect.class);

    private static final String METRIC_NAME = "smart.ticket.mq.consume.cost";

    private final MeterRegistry meterRegistry;

    private final AspectExpressionResolver expressionResolver = new AspectExpressionResolver();

    public MqConsumeTraceAspect(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Around("@annotation(mqConsumeTrace)")
    public Object trace(ProceedingJoinPoint joinPoint, MqConsumeTrace mqConsumeTrace) throws Throwable {
        long startedAt = System.nanoTime();
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String messageId = expressionResolver.resolve(
                method,
                joinPoint.getArgs(),
                null,
                mqConsumeTrace.messageId(),
                LOGGER
        );
        String businessKey = expressionResolver.resolve(
                method,
                joinPoint.getArgs(),
                null,
                mqConsumeTrace.businessKey(),
                LOGGER
        );
        String result = "success";
        try {
            LOGGER.debug("MQ consume started, topic={}, consumerGroup={}, messageId={}, businessKey={}",
                    mqConsumeTrace.topic(), mqConsumeTrace.consumerGroup(), messageId, businessKey);
            return joinPoint.proceed();
        } catch (Throwable error) {
            result = "failed";
            LOGGER.warn("MQ consume failed, topic={}, consumerGroup={}, messageId={}, businessKey={}",
                    mqConsumeTrace.topic(), mqConsumeTrace.consumerGroup(), messageId, businessKey, error);
            throw error;
        } finally {
            long costNanos = System.nanoTime() - startedAt;
            recordMetric(mqConsumeTrace.topic(), mqConsumeTrace.consumerGroup(), result, costNanos);
            logCost(mqConsumeTrace, messageId, businessKey, result, costNanos);
        }
    }

    private void recordMetric(String topic, String consumerGroup, String result, long costNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder(METRIC_NAME)
                .tag("topic", topic)
                .tag("consumerGroup", consumerGroup)
                .tag("result", result)
                .register(meterRegistry)
                .record(costNanos, TimeUnit.NANOSECONDS);
    }

    private void logCost(MqConsumeTrace mqConsumeTrace,
                         String messageId,
                         String businessKey,
                         String result,
                         long costNanos) {
        long costMs = TimeUnit.NANOSECONDS.toMillis(costNanos);
        if (costMs >= mqConsumeTrace.slowThresholdMs()) {
            LOGGER.warn("Slow MQ consume detected, topic={}, consumerGroup={}, messageId={}, businessKey={}, result={}, costMs={}",
                    mqConsumeTrace.topic(), mqConsumeTrace.consumerGroup(), messageId, businessKey, result, costMs);
            return;
        }
        LOGGER.debug("MQ consume finished, topic={}, consumerGroup={}, messageId={}, businessKey={}, result={}, costMs={}",
                mqConsumeTrace.topic(), mqConsumeTrace.consumerGroup(), messageId, businessKey, result, costMs);
    }
}
