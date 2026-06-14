package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.domain.entity.AdminOperationLog;
import com.zewbby.smartticket.enums.AdminOperationResultEnum;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.mapper.AdminOperationLogMapper;
import com.zewbby.smartticket.ratelimit.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOperationLogServiceImplTest {

    @Mock
    private AdminOperationLogMapper adminOperationLogMapper;

    @Mock
    private ClientIpResolver clientIpResolver;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void recordSuccessWritesSafeAuditLog() {
        AdminOperationLogServiceImpl service = new AdminOperationLogServiceImpl(adminOperationLogMapper, clientIpResolver);
        UserContext.setUser(2L, "admin", "ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/local-messages/MSG1/retry");
        request.addParameter("token", "secret-token");
        request.addParameter("password", "plain-password");
        request.addParameter("reason", "manual");
        request.addHeader("X-Trace-Id", "TRACE-1");
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        service.recordSuccess(AdminOperationTypeEnum.LOCAL_MESSAGE_RETRY, "LOCAL_MESSAGE", "MSG1", request);

        ArgumentCaptor<AdminOperationLog> captor = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(adminOperationLogMapper).insert(captor.capture());
        AdminOperationLog log = captor.getValue();
        assertThat(log.getOperatorUserId()).isEqualTo(2L);
        assertThat(log.getOperatorUsername()).isEqualTo("admin");
        assertThat(log.getOperatorRole()).isEqualTo("ADMIN");
        assertThat(log.getOperationType()).isEqualTo("LOCAL_MESSAGE_RETRY");
        assertThat(log.getOperationResult()).isEqualTo(AdminOperationResultEnum.SUCCESS.getCode());
        assertThat(log.getRequestParams()).contains("token=***");
        assertThat(log.getRequestParams()).contains("password=***");
        assertThat(log.getRequestParams()).contains("reason=manual");
        assertThat(log.getRequestParams()).doesNotContain("secret-token");
        assertThat(log.getRequestParams()).doesNotContain("plain-password");
        assertThat(log.getTraceId()).isEqualTo("TRACE-1");
        assertThat(log.getClientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void recordFailureWritesErrorMessage() {
        AdminOperationLogServiceImpl service = new AdminOperationLogServiceImpl(adminOperationLogMapper, clientIpResolver);
        UserContext.setUser(2L, "admin", "ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/dead-letters/1/retry");
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        service.recordFailure(AdminOperationTypeEnum.DEAD_LETTER_RETRY,
                "DEAD_LETTER_MESSAGE", "1", new RuntimeException("retry failed"), request);

        ArgumentCaptor<AdminOperationLog> captor = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(adminOperationLogMapper).insert(captor.capture());
        AdminOperationLog log = captor.getValue();
        assertThat(log.getOperationResult()).isEqualTo(AdminOperationResultEnum.FAILED.getCode());
        assertThat(log.getErrorMessage()).isEqualTo("retry failed");
    }
}
