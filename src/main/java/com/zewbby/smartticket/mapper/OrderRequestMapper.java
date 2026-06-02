package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderRequestMapper {

    int insert(TicketOrderRequest request);

    TicketOrderRequest selectByRequestId(@Param("requestId") String requestId);

    TicketOrderRequest selectByRequestIdAndUserId(@Param("requestId") String requestId,
                                                  @Param("userId") Long userId);

    TicketOrderRequest selectById(@Param("id") Long id);

    TicketOrderRequest selectByOrderId(@Param("orderId") Long orderId);

    int markPreDeducted(@Param("id") Long id,
                        @Param("deductedQuantity") Integer deductedQuantity,
                        @Param("deductedAt") java.time.LocalDateTime deductedAt);

    int markQueued(@Param("id") Long id, @Param("messageId") String messageId);

    int refreshQueuedMessage(@Param("id") Long id, @Param("messageId") String messageId);

    int tryMarkProcessing(@Param("requestId") String requestId);

    int markSuccess(@Param("id") Long id, @Param("orderId") Long orderId);

    int markFailed(@Param("id") Long id, @Param("failReason") String failReason);

    int markProcessingTimeout(@Param("id") Long id, @Param("failReason") String failReason);

    int resetForManualRetry(@Param("id") Long id);

    int tryMarkCompensating(@Param("id") Long id);

    int markCompensateFailed(@Param("id") Long id, @Param("failReason") String failReason);

    int markCompensated(@Param("id") Long id,
                        @Param("compensatedAt") java.time.LocalDateTime compensatedAt);

    TicketOrderRequest selectProcessingByRequestId(@Param("requestId") String requestId);

    Integer sumInFlightDeductedQuantity(@Param("ticketCategoryId") Long ticketCategoryId);

    List<TicketOrderRequest> selectFailedRequestsNeedCompensation(@Param("limit") Integer limit);
}
