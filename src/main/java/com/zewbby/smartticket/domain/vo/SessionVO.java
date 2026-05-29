package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionVO {

    private Long id;

    private Long showId;

    private Long venueId;

    private String venueName;

    private String city;

    private String address;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private List<TicketCategoryVO> ticketCategories;
}
