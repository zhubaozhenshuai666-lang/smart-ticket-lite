package com.zewbby.smartticket.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class OperationCostAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationCostAspect.class);

    private static final String METRIC_NAME = "smart.ticket.operation.cost";

    private final MeterRegistry meterRegistry;

    public OperationCostAspect(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Around("@annotation(monitoredOperation)")
    public Object recordCost(ProceedingJoinPoint joinPoint, MonitoredOperation monitoredOperation) throws Throwable {
        long startedAt = System.nanoTime();
        String result = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable error) {
            result = "failed";
            throw error;
        } finally {
            long costNanos = System.nanoTime() - startedAt;
            recordMetric(monitoredOperation.value(), result, costNanos);
            logCost(joinPoint, monitoredOperation, result, costNanos);
        }
    }

    private void recordMetric(String operation, String result, long costNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder(METRIC_NAME)
                .tag("operation", operation)
                .tag("result", result)
                .register(meterRegistry)
                .record(costNanos, TimeUnit.NANOSECONDS);
    }

    private void logCost(ProceedingJoinPoint joinPoint,
                         MonitoredOperation monitoredOperation,
                         String result,
                         long costNanos) {
        long costMs = TimeUnit.NANOSECONDS.toMillis(costNanos);
        if (costMs >= monitoredOperation.slowThresholdMs()) {
            LOGGER.warn("Slow operation detected, operation={}, method={}, result={}, costMs={}",
                    monitoredOperation.value(), joinPoint.getSignature().toShortString(), result, costMs);
            return;
        }
        LOGGER.debug("Operation cost, operation={}, method={}, result={}, costMs={}",
                monitoredOperation.value(), joinPoint.getSignature().toShortString(), result, costMs);
    }
}
