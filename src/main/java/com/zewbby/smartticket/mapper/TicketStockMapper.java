package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketStock;
import org.apache.ibatis.annotations.Param;

public interface TicketStockMapper {

    TicketStock selectByTicketCategoryId(Long ticketCategoryId);

    int decreaseStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);

    int rollbackStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);

    int confirmStock(@Param("ticketCategoryId") Long ticketCategoryId, @Param("quantity") Integer quantity);
}
