package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.StockConsistencyRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockConsistencyRecordMapper {

    int insert(StockConsistencyRecord record);

    StockConsistencyRecord selectById(@Param("id") Long id);

    List<StockConsistencyRecord> selectRecent(@Param("status") String status,
                                              @Param("limit") Integer limit);

    Long countByStatus(@Param("status") String status);

    int markRepaired(@Param("id") Long id,
                     @Param("repairStrategy") String repairStrategy,
                     @Param("repairResult") String repairResult,
                     @Param("repairedAt") LocalDateTime repairedAt);

    int markFailed(@Param("id") Long id,
                   @Param("repairStrategy") String repairStrategy,
                   @Param("repairResult") String repairResult);

    int markIgnored(@Param("id") Long id,
                    @Param("repairResult") String repairResult);
}
