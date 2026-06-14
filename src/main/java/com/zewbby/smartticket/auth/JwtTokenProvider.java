package com.zewbby.smartticket.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.enums.UserRoleEnum;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JwtProperties jwtProperties;

    private final ObjectMapper objectMapper;

    public JwtTokenProvider(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
    }

    public String generateToken(UserAccount user) {
        Instant issuedAt = Instant.now();
        Instant expireAt = issuedAt.plusSeconds(jwtProperties.getExpireMinutes() * 60L);
        String jti = UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId());
        payload.put("username", user.getUsername());
        payload.put("phone", user.getPhone());
        /*
         * JWT 中放 roleCode 可以让普通业务入口少查一次数据库。
         * 代价是用户角色变更后，旧 token 中的角色不会立即变化，所以 /api/admin/** 会再查 DB 做二次确认。
         */
        payload.put("roleCode", UserRoleEnum.normalize(user.getRoleCode()).getCode());
        payload.put("jti", jti);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expireAt.getEpochSecond());

        String headerPart = base64Url(toJsonBytes(header));
        String payloadPart = base64Url(toJsonBytes(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + sign(signingInput);
    }

    /**
     * 验证token是否被篡改过
     * @param token
     * @return
     */
    public JwtUserClaims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, ErrorMessageConstant.UNAUTHORIZED);
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
        }

        Map<String, Object> payload = parsePayload(parts[1]);
        Long userId = numberValue(payload.get("userId"), ErrorMessageConstant.TOKEN_INVALID).longValue();
        String username = stringValue(payload.get("username"));
        String phone = stringValue(payload.get("phone"));
        String roleCode = UserRoleEnum.normalize(stringValue(payload.get("roleCode"))).getCode();
        String jti = requiredStringValue(payload.get("jti"), ErrorMessageConstant.TOKEN_INVALID);
        Instant issuedAt = Instant.ofEpochSecond(numberValue(payload.get("iat"), ErrorMessageConstant.TOKEN_INVALID).longValue());
        Instant expireAt = Instant.ofEpochSecond(numberValue(payload.get("exp"), ErrorMessageConstant.TOKEN_INVALID).longValue());

        if (!Instant.now().isBefore(expireAt)) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_EXPIRED);
        }

        return new JwtUserClaims(userId, username, phone, roleCode, jti, issuedAt, expireAt);
    }

    public Instant getExpireAt() {
        return Instant.now().plusSeconds(jwtProperties.getExpireMinutes() * 60L);
    }

    private Map<String, Object> parsePayload(String payloadPart) {
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadPart);
            return objectMapper.readValue(payloadBytes, MAP_TYPE);
        } catch (Exception exception) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
        }
    }

    private Number numberValue(Object value, String errorMessage) {
        if (value instanceof Number number) {
            return number;
        }
        throw new BusinessException(401, errorMessage);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String requiredStringValue(Object value, String errorMessage) {
        String stringValue = stringValue(value);
        if (stringValue == null || stringValue.isBlank()) {
            throw new BusinessException(401, errorMessage);
        }
        return stringValue;
    }

    private byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT序列化失败", exception);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretBytes(), HMAC_SHA256));
            return base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("JWT签名失败", exception);
        }
    }

    private byte[] secretBytes() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret长度不能少于32字节");
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
