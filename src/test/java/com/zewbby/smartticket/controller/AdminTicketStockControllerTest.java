package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.dto.AdjustStockRequest;
import com.zewbby.smartticket.domain.dto.InitStockRequest;
import com.zewbby.smartticket.domain.vo.AdminStockVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTicketStockControllerTest {

    @Mock
    private AdminBusinessService adminBusinessService;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private HttpServletRequest request;

    @Test
    void initStockSuccessWritesAuditLog() {
        AdminTicketStockController controller = new AdminTicketStockController(
                adminBusinessService,
                adminOperationLogService
        );
        InitStockRequest body = new InitStockRequest();
        body.setAvailableStock(100);
        when(adminBusinessService.initStock(2L, body)).thenReturn(new AdminStockVO());

        controller.initStock(2L, body, request);

        verify(adminOperationLogService).recordSuccess(
                AdminOperationTypeEnum.STOCK_INIT,
                "TICKET_STOCK",
                "2",
                request
        );
    }

    @Test
    void adjustStockFailureWritesAuditLog() {
        AdminTicketStockController controller = new AdminTicketStockController(
                adminBusinessService,
                adminOperationLogService
        );
        AdjustStockRequest body = new AdjustStockRequest();
        body.setAdjustQuantity(-100);
        body.setReason("错误扣减");

        assertThatThrownBy(() -> controller.adjustStock(2L, body, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存调整必须先创建调整申请");
        verify(adminBusinessService, never()).adjustStock(any(), any());
        verify(adminOperationLogService).recordFailure(
                eq(AdminOperationTypeEnum.STOCK_ADJUST),
                eq("TICKET_STOCK"),
                eq("2"),
                org.mockito.ArgumentMatchers.any(BusinessException.class),
                eq(request)
        );
    }
}
