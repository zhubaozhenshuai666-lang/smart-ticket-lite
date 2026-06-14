package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseVO {

    private Long userId;

    private String username;

    private String phone;

    private String roleCode;

    private String token;

    private LocalDateTime expireAt;
}
