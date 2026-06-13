package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaitingRoomAdmissionGrantVO {

    private Long userId;

    private String token;

    private long expireSeconds;
}
