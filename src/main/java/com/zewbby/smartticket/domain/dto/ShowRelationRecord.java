package com.zewbby.smartticket.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowRelationRecord {

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;
}
