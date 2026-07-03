package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.service.LocalMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminLocalMessageControllerTest {

    @Mock
    private LocalMessageService localMessageService;

    @Test
    void retryDelegatesToService() {
        AdminLocalMessageController controller = new AdminLocalMessageController(localMessageService);

        controller.retry("MSG1");

        verify(localMessageService).retryManually("MSG1");
    }

    @Test
    void retryRethrowsServiceException() {
        AdminLocalMessageController controller = new AdminLocalMessageController(localMessageService);
        BusinessException exception = new BusinessException("retry failed");
        doThrow(exception).when(localMessageService).retryManually("MSG1");

        assertThatThrownBy(() -> controller.retry("MSG1"))
                .isSameAs(exception);

        verify(localMessageService).retryManually("MSG1");
    }
}
