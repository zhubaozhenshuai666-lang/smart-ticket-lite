package com.zewbby.smartticket.domain.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockChangedEvent {

    private String eventId;

    private String eventType;

    private Long ticketCategoryId;

    private Long orderId;

    private String changeType;

    private Integer quantity;

    private LocalDateTime occurredAt;
}
