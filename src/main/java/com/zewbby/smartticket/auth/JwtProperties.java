package com.zewbby.smartticket.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smart-ticket.jwt")
public class JwtProperties {

    private String secret;

    private long expireMinutes = 120L;
}
