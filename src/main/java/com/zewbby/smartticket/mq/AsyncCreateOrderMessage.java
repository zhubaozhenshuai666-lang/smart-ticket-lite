package com.zewbby.smartticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncCreateOrderMessage {

    private String requestId;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;

    private Integer quantity;
}
