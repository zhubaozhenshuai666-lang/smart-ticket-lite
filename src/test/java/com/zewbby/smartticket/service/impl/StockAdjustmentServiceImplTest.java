package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.entity.StockAdjustmentRecord;
import com.zewbby.smartticket.domain.vo.AdminStockVO;
import com.zewbby.smartticket.enums.StockAdjustmentStatusEnum;
import com.zewbby.smartticket.mapper.StockAdjustmentRecordMapper;
import com.zewbby.smartticket.service.AdminBusinessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceImplTest {

    @Mock
    private StockAdjustmentRecordMapper stockAdjustmentRecordMapper;

    @Mock
    private AdminBusinessService adminBusinessService;

    private StockAdjustmentServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUser(7L, "admin", "ADMIN");
        service = new StockAdjustmentServiceImpl(stockAdjustmentRecordMapper, adminBusinessService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createAdjustmentDoesNotChangeStockImmediately() {
        when(adminBusinessService.getStock(2L)).thenReturn(stockView(100, 100));
        CreateStockAdjustmentRequest request = new CreateStockAdjustmentRequest();
        request.setTicketCategoryId(2L);
        request.setAdjustQuantity(10);
        request.setReason("追加放票");

        var result = service.createAdjustment(request);

        ArgumentCaptor<StockAdjustmentRecord> captor = ArgumentCaptor.forClass(StockAdjustmentRecord.class);
        verify(stockAdjustmentRecordMapper).insert(captor.capture());
        verify(adminBusinessService, never()).adjustStock(eq(2L), any());
        assertThat(captor.getValue().getStatus()).isEqualTo(StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode());
        assertThat(captor.getValue().getOperatorUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getConfirmToken()).isNotBlank();
        assertThat(result.getStatus()).isEqualTo(StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode());
    }

    @Test
    void createAdjustmentRejectsBlankReason() {
        CreateStockAdjustmentRequest request = new CreateStockAdjustmentRequest();
        request.setTicketCategoryId(2L);
        request.setAdjustQuantity(10);
        request.setReason(" ");

        assertThatThrownBy(() -> service.createAdjustment(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存调整原因不能为空");
        verify(stockAdjustmentRecordMapper, never()).insert(any());
    }

    @Test
    void confirmAdjustmentAppliesStockAndRecordsBeforeAfterAndRollbackSuggestion() {
        StockAdjustmentRecord pending = record(1L, StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode(), 10);
        StockAdjustmentRecord confirmed = record(1L, StockAdjustmentStatusEnum.CONFIRMED.getCode(), 10);
        StockAdjustmentRecord applied = record(1L, StockAdjustmentStatusEnum.ROLLBACK_RECORDED.getCode(), 10);
        applied.setBeforeAvailableStock(100);
        applied.setAfterAvailableStock(110);
        applied.setBeforeRedisStock(100);
        applied.setAfterRedisStock(110);
        applied.setRollbackRecordId(2L);
        when(stockAdjustmentRecordMapper.selectById(1L)).thenReturn(pending, confirmed, applied);
        when(stockAdjustmentRecordMapper.markConfirmed(eq(1L), eq("token-1"), any(LocalDateTime.class))).thenReturn(1);
        when(adminBusinessService.getStock(2L)).thenReturn(stockView(100, 100));
        when(adminBusinessService.adjustStock(eq(2L), any())).thenReturn(stockView(110, 110));
        when(stockAdjustmentRecordMapper.markApplied(1L, 100, 110, 100, 110, 2L)).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            StockAdjustmentRecord rollback = invocation.getArgument(0);
            rollback.setId(2L);
            return 1;
        }).when(stockAdjustmentRecordMapper).insert(any(StockAdjustmentRecord.class));
        ConfirmStockAdjustmentRequest request = new ConfirmStockAdjustmentRequest();
        request.setConfirmToken("token-1");

        var result = service.confirmAdjustment(1L, request);

        ArgumentCaptor<StockAdjustmentRecord> rollbackCaptor = ArgumentCaptor.forClass(StockAdjustmentRecord.class);
        verify(stockAdjustmentRecordMapper).insert(rollbackCaptor.capture());
        assertThat(rollbackCaptor.getValue().getAdjustQuantity()).isEqualTo(-10);
        assertThat(rollbackCaptor.getValue().getStatus()).isEqualTo(StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode());
        verify(stockAdjustmentRecordMapper).markApplied(1L, 100, 110, 100, 110, 2L);
        assertThat(result.getBeforeAvailableStock()).isEqualTo(100);
        assertThat(result.getAfterAvailableStock()).isEqualTo(110);
        assertThat(result.getRollbackRecordId()).isEqualTo(2L);
    }

    @Test
    void repeatedConfirmDoesNotApplyStockAgain() {
        StockAdjustmentRecord applied = record(1L, StockAdjustmentStatusEnum.ROLLBACK_RECORDED.getCode(), 10);
        when(stockAdjustmentRecordMapper.selectById(1L)).thenReturn(applied);
        ConfirmStockAdjustmentRequest request = new ConfirmStockAdjustmentRequest();
        request.setConfirmToken("token-1");

        service.confirmAdjustment(1L, request);

        verify(stockAdjustmentRecordMapper, never()).markConfirmed(eq(1L), eq("token-1"), any());
        verify(adminBusinessService, never()).adjustStock(eq(2L), any());
    }

    private StockAdjustmentRecord record(Long id, String status, Integer adjustQuantity) {
        StockAdjustmentRecord record = new StockAdjustmentRecord();
        record.setId(id);
        record.setTicketCategoryId(2L);
        record.setOperatorUserId(7L);
        record.setAdjustQuantity(adjustQuantity);
        record.setReason("追加放票");
        record.setStatus(status);
        record.setConfirmToken("token-1");
        return record;
    }

    private AdminStockVO stockView(Integer mysqlAvailable, Integer redisAvailable) {
        return new AdminStockVO(
                2L,
                mysqlAvailable,
                mysqlAvailable,
                0,
                0,
                redisAvailable,
                false,
                0,
                redisAvailable,
                0,
                true,
                true
        );
    }
}
