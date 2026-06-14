package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.vo.StockAdjustmentRecordVO;

import java.util.List;

public interface StockAdjustmentService {

    StockAdjustmentRecordVO createAdjustment(CreateStockAdjustmentRequest request);

    StockAdjustmentRecordVO confirmAdjustment(Long id, ConfirmStockAdjustmentRequest request);

    List<StockAdjustmentRecordVO> listRecent(Integer limit);
}
