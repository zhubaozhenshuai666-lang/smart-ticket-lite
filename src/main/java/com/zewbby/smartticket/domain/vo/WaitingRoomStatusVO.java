package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaitingRoomStatusVO {

    private Long ticketCategoryId;

    private Long userId;

    private boolean queued;

    private Long position;

    private Long queueSize;
}
