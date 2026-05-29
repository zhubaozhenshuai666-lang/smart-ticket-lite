package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketOrder;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderMapper {

    int insert(TicketOrder order);

    TicketOrder selectById(Long id);

    TicketOrder selectByOrderNo(String orderNo);

    List<TicketOrder> selectByUserId(Long userId);

    List<TicketOrder> selectExpiredPendingOrders(@Param("now") LocalDateTime now,
                                                 @Param("limit") Integer limit);

    int updateCancelStatus(@Param("id") Long id,
                           @Param("oldStatus") String oldStatus,
                           @Param("newStatus") String newStatus,
                           @Param("cancelTime") LocalDateTime cancelTime,
                           @Param("cancelReason") String cancelReason);

    int updatePayStatus(@Param("id") Long id,
                        @Param("oldStatus") String oldStatus,
                        @Param("newStatus") String newStatus,
                        @Param("payTime") LocalDateTime payTime);

    int updateCloseStatus(@Param("id") Long id,
                          @Param("oldStatus") String oldStatus,
                          @Param("newStatus") String newStatus,
                          @Param("closeTime") LocalDateTime closeTime,
                          @Param("cancelReason") String cancelReason);
}
