package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.vo.StockAdjustmentRecordVO;
import com.zewbby.smartticket.service.StockAdjustmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStockAdjustmentControllerTest {

    @Mock
    private StockAdjustmentService stockAdjustmentService;

    @Test
    void createAdjustmentDelegatesToService() {
        AdminStockAdjustmentController controller = new AdminStockAdjustmentController(stockAdjustmentService);
        CreateStockAdjustmentRequest body = new CreateStockAdjustmentRequest();
        body.setTicketCategoryId(2L);
        body.setAdjustQuantity(10);
        body.setReason("追加放票");
        StockAdjustmentRecordVO vo = new StockAdjustmentRecordVO();
        vo.setId(9L);
        when(stockAdjustmentService.createAdjustment(body)).thenReturn(vo);

        StockAdjustmentRecordVO result = controller.createAdjustment(body).getData();

        assertThat(result).isSameAs(vo);
        verify(stockAdjustmentService).createAdjustment(body);
    }

    @Test
    void confirmAdjustmentRethrowsServiceException() {
        AdminStockAdjustmentController controller = new AdminStockAdjustmentController(stockAdjustmentService);
        ConfirmStockAdjustmentRequest body = new ConfirmStockAdjustmentRequest();
        body.setConfirmToken("bad-token");
        BusinessException exception = new BusinessException("库存调整确认失败");
        when(stockAdjustmentService.confirmAdjustment(9L, body)).thenThrow(exception);

        assertThatThrownBy(() -> controller.confirmAdjustment(9L, body))
                .isSameAs(exception);
        verify(stockAdjustmentService).confirmAdjustment(9L, body);
    }
}
