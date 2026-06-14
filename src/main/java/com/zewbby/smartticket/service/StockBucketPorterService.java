package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.StockBucketPorterResult;

public interface StockBucketPorterService {

    StockBucketPorterResult moveReturnedStock(Long ticketCategoryId,
                                              Integer fromVersion,
                                              Integer toVersion,
                                              Integer fromBucketCount,
                                              Integer toBucketCount,
                                              Integer maxMoveQuantity);
}
