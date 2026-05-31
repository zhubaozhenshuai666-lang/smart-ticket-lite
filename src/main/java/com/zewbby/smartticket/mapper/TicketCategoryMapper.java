package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketCategory;
import org.apache.ibatis.annotations.Param;

public interface TicketCategoryMapper {

    TicketCategory selectById(Long id);

    boolean existsShowSessionTicketCategoryRelation(@Param("showId") Long showId,
                                                    @Param("sessionId") Long sessionId,
                                                    @Param("ticketCategoryId") Long ticketCategoryId);
}
