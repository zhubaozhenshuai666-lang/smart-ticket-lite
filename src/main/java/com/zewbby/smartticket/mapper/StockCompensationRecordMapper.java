package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.StockCompensationRecord;
import org.apache.ibatis.annotations.Param;

public interface StockCompensationRecordMapper {

    int insert(StockCompensationRecord record);

    Long countByStatus(@Param("status") String status);
}
