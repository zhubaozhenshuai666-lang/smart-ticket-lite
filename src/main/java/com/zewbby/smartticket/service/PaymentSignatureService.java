package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.PaymentProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class PaymentSignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final String NONCE_KEY_PREFIX = "payment:mock:nonce:";

    private final PaymentProperties paymentProperties;

    private final StringRedisTemplate stringRedisTemplate;

    public PaymentSignatureService(PaymentProperties paymentProperties,
                                   StringRedisTemplate stringRedisTemplate) {
        this.paymentProperties = paymentProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 校验 mock-pay 回调签名，并在提供 nonce 时做 Redis 防重放。
     *
     * mock-pay 虽然不是第三方支付，但它仍然会把订单从 PENDING_PAYMENT 推到 PAID，
     * 还会触发库存 locked_stock -> sold_stock。这样的接口如果裸露，任何拿到 paymentNo 的人
     * 都能伪造支付成功，所以必须给模拟回调也加安全边界。
     *
     * @param request mock 支付回调请求。
     */
    public void verify(MockPaymentRequest request) {
        if (request == null || request.getPaymentNo() == null || request.getPaymentNo().isBlank()
                || request.getSuccess() == null || request.getTimestamp() == null
                || request.getSignature() == null || request.getSignature().isBlank()) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID);
        }

        long now = Instant.now().toEpochMilli();
        long toleranceMillis = paymentProperties.getCallbackTimestampToleranceSeconds() * 1000L;
        if (Math.abs(now - request.getTimestamp()) > toleranceMillis) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_CALLBACK_EXPIRED);
        }

        String expected = sign(
                request.getPaymentNo(),
                request.getSuccess(),
                request.getTimestamp(),
                request.getNonce()
        );
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                request.getSignature().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID);
        }

        consumeNonceIfPresent(request);
    }

    /**
     * 生成 HMAC-SHA256 签名。
     *
     * 签名字段固定为 paymentNo、success、timestamp、nonce 和配置里的 secret。
     * 这里不接受前端传金额，因为支付金额必须来自 ticket_order.total_amount 这个订单快照金额。
     */
    public String sign(String paymentNo, Boolean success, Long timestamp, String nonce) {
        try {
            String canonical = paymentNo + "\n"
                    + success + "\n"
                    + timestamp + "\n"
                    + normalizeNonce(nonce);
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(
                    paymentProperties.getMockCallbackSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            ));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("支付回调签名生成失败", exception);
        }
    }

    private void consumeNonceIfPresent(MockPaymentRequest request) {
        if (request.getNonce() == null || request.getNonce().isBlank()) {
            return;
        }
        String key = NONCE_KEY_PREFIX + request.getNonce().trim();
        Boolean consumed = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                request.getPaymentNo(),
                Duration.ofSeconds(paymentProperties.getCallbackNonceTtlSeconds())
        );
        if (!Boolean.TRUE.equals(consumed)) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_CALLBACK_REPLAY);
        }
    }

    private String normalizeNonce(String nonce) {
        return nonce == null ? "" : nonce.trim();
    }
}
