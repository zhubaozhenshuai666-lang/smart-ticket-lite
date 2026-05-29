package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.TicketCategory;

public interface TicketCategoryMapper {

    TicketCategory selectById(Long id);
}
