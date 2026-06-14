package com.zewbby.smartticket.domain.vo;

import com.zewbby.smartticket.domain.entity.StockAdjustmentRecord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockAdjustmentRecordVO {

    private Long id;

    private Long ticketCategoryId;

    private Long operatorUserId;

    private Integer adjustQuantity;

    private Integer beforeAvailableStock;

    private Integer afterAvailableStock;

    private Integer beforeRedisStock;

    private Integer afterRedisStock;

    private String reason;

    private String status;

    private String confirmToken;

    private LocalDateTime confirmedAt;

    private Boolean rollbackAvailable;

    private Long rollbackRecordId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static StockAdjustmentRecordVO from(StockAdjustmentRecord record) {
        if (record == null) {
            return null;
        }
        return new StockAdjustmentRecordVO(
                record.getId(),
                record.getTicketCategoryId(),
                record.getOperatorUserId(),
                record.getAdjustQuantity(),
                record.getBeforeAvailableStock(),
                record.getAfterAvailableStock(),
                record.getBeforeRedisStock(),
                record.getAfterRedisStock(),
                record.getReason(),
                record.getStatus(),
                record.getConfirmToken(),
                record.getConfirmedAt(),
                record.getRollbackAvailable(),
                record.getRollbackRecordId(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
