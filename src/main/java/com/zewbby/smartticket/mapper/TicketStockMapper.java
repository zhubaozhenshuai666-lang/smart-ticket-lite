package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketStock;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketStockMapper {

    TicketStock selectByTicketCategoryId(Long ticketCategoryId);

    List<TicketStock> selectAll();

    List<TicketStock> selectPageAfterId(@Param("lastId") Long lastId, @Param("limit") Integer limit);

    int insert(TicketStock ticketStock);

    int initExistingStock(@Param("ticketCategoryId") Long ticketCategoryId,
                          @Param("availableStock") Integer availableStock);

    int adjustAvailableStock(@Param("ticketCategoryId") Long ticketCategoryId,
                             @Param("adjustQuantity") Integer adjustQuantity);

    int decreaseStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);

    int rollbackStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);

    int confirmStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);
}
