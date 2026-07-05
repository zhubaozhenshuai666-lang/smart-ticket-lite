package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketOrder;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderMapper {

    int insert(TicketOrder order);

    int insertBatch(@Param("orders") List<TicketOrder> orders);

    TicketOrder selectById(Long id);

    TicketOrder selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    TicketOrder selectByOrderNo(String orderNo);

    List<TicketOrder> selectByOrderNos(@Param("orderNos") List<String> orderNos);

    List<TicketOrder> selectByUserId(Long userId);

    List<TicketOrder> selectExpiredPendingOrders(@Param("now") LocalDateTime now,
                                                 @Param("limit") Integer limit);

    int countPendingByShowId(@Param("showId") Long showId);

    int countPendingBySessionId(@Param("sessionId") Long sessionId);

    int countPendingByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    int countByTicketCategoryId(@Param("ticketCategoryId") Long ticketCategoryId);

    int updateCancelStatusByUserId(@Param("id") Long id,
                                   @Param("userId") Long userId,
                                   @Param("oldStatus") String oldStatus,
                                   @Param("newStatus") String newStatus,
                                   @Param("cancelTime") LocalDateTime cancelTime,
                                   @Param("cancelReason") String cancelReason);

    int updatePayStatusByUserId(@Param("id") Long id,
                                @Param("userId") Long userId,
                                @Param("oldStatus") String oldStatus,
                                @Param("newStatus") String newStatus,
                                @Param("payTime") LocalDateTime payTime);

    int updateCloseStatus(@Param("id") Long id,
                          @Param("oldStatus") String oldStatus,
                          @Param("newStatus") String newStatus,
                          @Param("closeTime") LocalDateTime closeTime,
                          @Param("cancelReason") String cancelReason);
}
