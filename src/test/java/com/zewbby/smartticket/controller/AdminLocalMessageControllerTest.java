package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminOperationLogService;
import com.zewbby.smartticket.service.LocalMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminLocalMessageControllerTest {

    @Mock
    private LocalMessageService localMessageService;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Test
    void retryWritesSuccessAuditLog() {
        AdminLocalMessageController controller = new AdminLocalMessageController(
                localMessageService,
                adminOperationLogService
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/local-messages/MSG1/retry");

        controller.retry("MSG1", request);

        verify(localMessageService).retryManually("MSG1");
        verify(adminOperationLogService).recordSuccess(AdminOperationTypeEnum.LOCAL_MESSAGE_RETRY,
                "LOCAL_MESSAGE", "MSG1", request);
    }

    @Test
    void retryWritesFailureAuditLogWhenOperationFails() {
        AdminLocalMessageController controller = new AdminLocalMessageController(
                localMessageService,
                adminOperationLogService
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/local-messages/MSG1/retry");
        BusinessException exception = new BusinessException("retry failed");
        doThrow(exception).when(localMessageService).retryManually("MSG1");

        assertThatThrownBy(() -> controller.retry("MSG1", request))
                .isSameAs(exception);

        verify(adminOperationLogService).recordFailure(AdminOperationTypeEnum.LOCAL_MESSAGE_RETRY,
                "LOCAL_MESSAGE", "MSG1", exception, request);
    }
}
