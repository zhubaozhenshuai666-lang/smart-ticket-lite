package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.Venue;

public interface VenueMapper {

    Venue selectById(Long id);
}
