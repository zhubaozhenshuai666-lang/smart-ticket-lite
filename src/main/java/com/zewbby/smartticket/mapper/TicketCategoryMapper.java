package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketCategoryMapper {

    TicketCategory selectById(Long id);

    List<TicketCategory> adminSelectBySessionId(@Param("sessionId") Long sessionId);

    int insert(TicketCategory ticketCategory);

    int update(TicketCategory ticketCategory);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    boolean existsShowSessionTicketCategoryRelation(@Param("showId") Long showId,
                                                    @Param("sessionId") Long sessionId,
                                                    @Param("ticketCategoryId") Long ticketCategoryId);

    OrderSnapshot selectOrderSnapshot(@Param("showId") Long showId,
                                      @Param("sessionId") Long sessionId,
                                      @Param("ticketCategoryId") Long ticketCategoryId);
}
