package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.PaymentProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSignatureServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PaymentSignatureService paymentSignatureService;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.setMockCallbackSecret("unit-test-mock-payment-secret-at-least-32-bytes");
        properties.setCallbackTimestampToleranceSeconds(300);
        properties.setCallbackNonceTtlSeconds(600);
        paymentSignatureService = new PaymentSignatureService(properties, stringRedisTemplate);
    }

    @Test
    void validSignaturePassesAndConsumesNonce() {
        Long timestamp = System.currentTimeMillis();
        String signature = paymentSignatureService.sign("PAY1", true, timestamp, "nonce-1");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("PAY1"), any(Duration.class))).thenReturn(true);

        paymentSignatureService.verify(new MockPaymentRequest("PAY1", true, timestamp, "nonce-1", signature));
    }

    @Test
    void validSignatureWithoutNoncePassesWithoutRedisReplayCheck() {
        Long timestamp = System.currentTimeMillis();
        String signature = paymentSignatureService.sign("PAY1", true, timestamp, null);

        paymentSignatureService.verify(new MockPaymentRequest("PAY1", true, timestamp, null, signature));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void wrongSignatureFails() {
        Long timestamp = System.currentTimeMillis();

        assertThatThrownBy(() -> paymentSignatureService.verify(new MockPaymentRequest(
                "PAY1",
                true,
                timestamp,
                "nonce-1",
                "bad-signature"
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID);
    }

    @Test
    void expiredTimestampFails() {
        Long expiredTimestamp = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
        String signature = paymentSignatureService.sign("PAY1", true, expiredTimestamp, "nonce-1");

        assertThatThrownBy(() -> paymentSignatureService.verify(new MockPaymentRequest(
                "PAY1",
                true,
                expiredTimestamp,
                "nonce-1",
                signature
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_CALLBACK_EXPIRED);
    }

    @Test
    void repeatedNonceFails() {
        Long timestamp = System.currentTimeMillis();
        String signature = paymentSignatureService.sign("PAY1", true, timestamp, "nonce-1");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("PAY1"), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> paymentSignatureService.verify(new MockPaymentRequest(
                "PAY1",
                true,
                timestamp,
                "nonce-1",
                signature
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_CALLBACK_REPLAY);
    }
}
