package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.payment")
public class PaymentProperties {

    /**
     * mock-pay 的内部回调密钥。
     *
     * 这不是第三方支付密钥，只是本地模拟支付时的安全边界。它必须来自配置，
     * 不能写死在 Java 代码里，否则泄露后所有环境都会被同一把密钥打穿。
     */
    private String mockCallbackSecret = "smart-ticket-mock-payment-secret-at-least-32-bytes";

    private long callbackTimestampToleranceSeconds = 300L;

    private long callbackNonceTtlSeconds = 600L;

    public String getMockCallbackSecret() {
        return mockCallbackSecret;
    }

    public void setMockCallbackSecret(String mockCallbackSecret) {
        this.mockCallbackSecret = mockCallbackSecret;
    }

    public long getCallbackTimestampToleranceSeconds() {
        return callbackTimestampToleranceSeconds;
    }

    public void setCallbackTimestampToleranceSeconds(long callbackTimestampToleranceSeconds) {
        this.callbackTimestampToleranceSeconds = callbackTimestampToleranceSeconds;
    }

    public long getCallbackNonceTtlSeconds() {
        return callbackNonceTtlSeconds;
    }

    public void setCallbackNonceTtlSeconds(long callbackNonceTtlSeconds) {
        this.callbackNonceTtlSeconds = callbackNonceTtlSeconds;
    }
}
