package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import org.apache.ibatis.annotations.Param;

public interface OrderRequestMapper {

    int insert(TicketOrderRequest request);

    TicketOrderRequest selectByRequestId(@Param("requestId") String requestId);

    TicketOrderRequest selectById(@Param("id") Long id);

    TicketOrderRequest selectByOrderId(@Param("orderId") Long orderId);

    int markSuccess(@Param("id") Long id, @Param("orderId") Long orderId);

    int markFailed(@Param("id") Long id, @Param("failReason") String failReason);

    TicketOrderRequest selectProcessingByRequestId(@Param("requestId") String requestId);
}
