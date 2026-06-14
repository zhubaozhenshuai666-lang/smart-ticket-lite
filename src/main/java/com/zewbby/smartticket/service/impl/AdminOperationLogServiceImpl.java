package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.domain.entity.AdminOperationLog;
import com.zewbby.smartticket.enums.AdminOperationResultEnum;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.mapper.AdminOperationLogMapper;
import com.zewbby.smartticket.ratelimit.ClientIpResolver;
import com.zewbby.smartticket.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private static final int REQUEST_URI_MAX_LENGTH = 255;

    private static final int REQUEST_METHOD_MAX_LENGTH = 16;

    private static final int REQUEST_PARAMS_MAX_LENGTH = 2000;

    private static final int ERROR_MESSAGE_MAX_LENGTH = 512;

    private static final int TRACE_ID_MAX_LENGTH = 64;

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final AdminOperationLogMapper adminOperationLogMapper;

    private final ClientIpResolver clientIpResolver;

    public AdminOperationLogServiceImpl(AdminOperationLogMapper adminOperationLogMapper,
                                        ClientIpResolver clientIpResolver) {
        this.adminOperationLogMapper = adminOperationLogMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void recordSuccess(AdminOperationTypeEnum operationType,
                              String resourceType,
                              String resourceId,
                              HttpServletRequest request) {
        record(operationType, resourceType, resourceId,
                AdminOperationResultEnum.SUCCESS.getCode(), null, request);
    }

    @Override
    public void recordFailure(AdminOperationTypeEnum operationType,
                              String resourceType,
                              String resourceId,
                              Throwable error,
                              HttpServletRequest request) {
        record(operationType, resourceType, resourceId,
                AdminOperationResultEnum.FAILED.getCode(),
                error == null ? null : error.getMessage(),
                request);
    }

    /**
     * 记录后台高风险操作。
     *
     * 高风险操作包括库存修复、消息重试、死信忽略、失败请求补偿等。它们会改变交易系统状态，
     * 不能只依赖日志打印，否则出问题时无法按操作者、资源和 traceId 追溯。
     * 审计日志故意不记录 token/password：token 是登录凭证，password 是敏感数据，进入审计表会扩大泄露面。
     */
    private void record(AdminOperationTypeEnum operationType,
                        String resourceType,
                        String resourceId,
                        String operationResult,
                        String errorMessage,
                        HttpServletRequest request) {
        AdminOperationLog log = new AdminOperationLog();
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setOperatorRole(UserContext.getRoleCode());
        log.setOperationType(operationType.getCode());
        log.setResourceType(trim(resourceType, 64));
        log.setResourceId(trim(resourceId, 128));
        log.setRequestUri(trim(request == null ? null : request.getRequestURI(), REQUEST_URI_MAX_LENGTH));
        log.setRequestMethod(trim(request == null ? null : request.getMethod(), REQUEST_METHOD_MAX_LENGTH));
        log.setRequestParams(trim(buildSafeRequestParams(request), REQUEST_PARAMS_MAX_LENGTH));
        log.setOperationResult(operationResult);
        log.setErrorMessage(trim(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        log.setClientIp(trim(request == null ? null : clientIpResolver.resolve(request), 64));
        log.setTraceId(trim(request == null ? null : request.getHeader(TRACE_ID_HEADER), TRACE_ID_MAX_LENGTH));
        log.setCreatedAt(LocalDateTime.now());
        adminOperationLogMapper.insert(log);
    }

    private String buildSafeRequestParams(HttpServletRequest request) {
        if (request == null || request.getParameterMap().isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            String value = isSensitiveKey(key)
                    ? "***"
                    : String.join(",", entry.getValue() == null ? new String[0] : entry.getValue());
            joiner.add(key + "=" + value);
        }
        return joiner.toString();
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return Arrays.asList("password", "token", "authorization", "secret").contains(normalized)
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret");
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
