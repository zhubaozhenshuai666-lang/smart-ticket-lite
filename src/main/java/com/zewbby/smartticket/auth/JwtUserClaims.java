package com.zewbby.smartticket.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtUserClaims {

    private Long userId;

    private String username;

    private String phone;

    private String roleCode;

    private String jti;

    private Instant issuedAt;

    private Instant expireAt;
}
