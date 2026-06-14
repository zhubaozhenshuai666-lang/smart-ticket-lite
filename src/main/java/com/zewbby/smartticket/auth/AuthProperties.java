package com.zewbby.smartticket.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smart-ticket.auth")
public class AuthProperties {

    private int loginFailThreshold = 5;

    private long loginLockMinutes = 10L;
}
