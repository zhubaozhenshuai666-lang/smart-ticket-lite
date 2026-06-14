package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.StockAdjustmentRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockAdjustmentRecordMapper {

    int insert(StockAdjustmentRecord record);

    StockAdjustmentRecord selectById(Long id);

    List<StockAdjustmentRecord> selectRecent(@Param("limit") Integer limit);

    int markConfirmed(@Param("id") Long id,
                      @Param("confirmToken") String confirmToken,
                      @Param("confirmedAt") LocalDateTime confirmedAt);

    int markApplied(@Param("id") Long id,
                    @Param("beforeAvailableStock") Integer beforeAvailableStock,
                    @Param("afterAvailableStock") Integer afterAvailableStock,
                    @Param("beforeRedisStock") Integer beforeRedisStock,
                    @Param("afterRedisStock") Integer afterRedisStock,
                    @Param("rollbackRecordId") Long rollbackRecordId);

    int markFailed(@Param("id") Long id);
}
