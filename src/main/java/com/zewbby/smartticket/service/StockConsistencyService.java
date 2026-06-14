package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.entity.StockConsistencyRecord;
import com.zewbby.smartticket.domain.vo.StockConsistencyVO;

import java.util.List;

public interface StockConsistencyService {

    StockConsistencyVO checkStockConsistency(Long ticketCategoryId);

    StockConsistencyVO checkOne(Long ticketCategoryId, String checkType);

    List<StockConsistencyVO> checkAll(String checkType, Integer batchSize);

    List<StockConsistencyRecord> listRecords(String status, Integer limit);

    void repairRecord(Long recordId);

    void ignoreRecord(Long recordId);

    int compensateFailedRequests(Integer batchSize);
}
