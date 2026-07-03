package com.zewbby.smartticket.aop;

import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AdminAuditAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditAspect.class);

    private final AdminOperationLogService adminOperationLogService;

    private final AspectExpressionResolver expressionResolver = new AspectExpressionResolver();

    public AdminAuditAspect(AdminOperationLogService adminOperationLogService) {
        this.adminOperationLogService = adminOperationLogService;
    }

    @Around("@annotation(adminAudit)")
    public Object audit(ProceedingJoinPoint joinPoint, AdminAudit adminAudit) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        try {
            Object result = joinPoint.proceed();
            String resourceId = resolveSuccessResourceId(method, joinPoint.getArgs(), result, adminAudit);
            recordSuccessAfterCommit(adminAudit.operation(), adminAudit.resourceType(), resourceId);
            return result;
        } catch (Throwable error) {
            String resourceId = expressionResolver.resolve(
                    method,
                    joinPoint.getArgs(),
                    null,
                    adminAudit.resourceId(),
                    LOGGER
            );
            safeRecordFailure(adminAudit.operation(), adminAudit.resourceType(), resourceId, error);
            throw error;
        }
    }

    private String resolveSuccessResourceId(Method method, Object[] args, Object result, AdminAudit adminAudit) {
        String expression = StringUtils.hasText(adminAudit.resultId())
                ? adminAudit.resultId()
                : adminAudit.resourceId();
        return expressionResolver.resolve(method, args, result, expression, LOGGER);
    }

    private void recordSuccessAfterCommit(AdminOperationTypeEnum operationType,
                                          String resourceType,
                                          String resourceId) {
        Runnable action = () -> safeRecordSuccess(operationType, resourceType, resourceId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void safeRecordSuccess(AdminOperationTypeEnum operationType,
                                   String resourceType,
                                   String resourceId) {
        try {
            adminOperationLogService.recordSuccess(operationType, resourceType, resourceId, currentRequest());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record admin audit success, operationType={}, resourceType={}, resourceId={}",
                    operationType.getCode(), resourceType, resourceId, exception);
        }
    }

    private void safeRecordFailure(AdminOperationTypeEnum operationType,
                                   String resourceType,
                                   String resourceId,
                                   Throwable error) {
        try {
            adminOperationLogService.recordFailure(operationType, resourceType, resourceId, error, currentRequest());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record admin audit failure, operationType={}, resourceType={}, resourceId={}",
                    operationType.getCode(), resourceType, resourceId, exception);
        }
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }
}
