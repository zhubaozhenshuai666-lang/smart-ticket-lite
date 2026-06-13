package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketStockBucket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketStockBucketMapper {

    int insert(TicketStockBucket bucket);

    int deleteByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    int deleteByTicketCategoryIdAndVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                                           @Param("bucketVersion") Integer bucketVersion);

    TicketStockBucket selectByBucket(@Param("ticketCategoryId") Long ticketCategoryId,
                                     @Param("bucketNo") Integer bucketNo);

    TicketStockBucket selectByVersionBucket(@Param("ticketCategoryId") Long ticketCategoryId,
                                            @Param("bucketVersion") Integer bucketVersion,
                                            @Param("bucketNo") Integer bucketNo);

    List<TicketStockBucket> selectByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    List<TicketStockBucket> selectByTicketCategoryIdAndVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                                                               @Param("bucketVersion") Integer bucketVersion);

    int countByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    int countByTicketCategoryIdAndVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                                          @Param("bucketVersion") Integer bucketVersion);

    int countLockedOrSoldByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    int countLockedOrSoldByTicketCategoryIdAndVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                                                      @Param("bucketVersion") Integer bucketVersion);

    int adjustAvailableStock(@Param("ticketCategoryId") Long ticketCategoryId,
                             @Param("bucketNo") Integer bucketNo,
                             @Param("adjustQuantity") Integer adjustQuantity);

    int adjustAvailableStockByVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                                      @Param("bucketVersion") Integer bucketVersion,
                                      @Param("bucketNo") Integer bucketNo,
                                      @Param("adjustQuantity") Integer adjustQuantity);

    int decreaseStock(@Param("ticketCategoryId") Long ticketCategoryId,
                      @Param("bucketNo") Integer bucketNo,
                      @Param("quantity") Integer quantity);

    int decreaseStockByVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                               @Param("bucketVersion") Integer bucketVersion,
                               @Param("bucketNo") Integer bucketNo,
                               @Param("quantity") Integer quantity);

    int rollbackStock(@Param("ticketCategoryId") Long ticketCategoryId,
                      @Param("bucketNo") Integer bucketNo,
                      @Param("quantity") Integer quantity);

    int rollbackStockByVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                               @Param("bucketVersion") Integer bucketVersion,
                               @Param("bucketNo") Integer bucketNo,
                               @Param("quantity") Integer quantity);

    int confirmStock(@Param("ticketCategoryId") Long ticketCategoryId,
                     @Param("bucketNo") Integer bucketNo,
                     @Param("quantity") Integer quantity);

    int confirmStockByVersion(@Param("ticketCategoryId") Long ticketCategoryId,
                              @Param("bucketVersion") Integer bucketVersion,
                              @Param("bucketNo") Integer bucketNo,
                              @Param("quantity") Integer quantity);
}
