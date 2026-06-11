package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.IdempotencyTokenVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class WaitingRoomService {

    private final StringRedisTemplate stringRedisTemplate;

    private final WaitingRoomProperties waitingRoomProperties;

    public WaitingRoomService(StringRedisTemplate stringRedisTemplate,
                              WaitingRoomProperties waitingRoomProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.waitingRoomProperties = waitingRoomProperties;
    }

    public boolean isEnabled() {
        return waitingRoomProperties.isEnabled();
    }

    public IdempotencyTokenVO issueAdmissionToken(Long userId, Long ticketCategoryId) {
        String token = "admit_" + UUID.randomUUID().toString().replace("-", "");
        String key = RedisKeyConstant.waitingRoomAdmissionTokenKey(ticketCategoryId, userId, token);
        long expireSeconds = waitingRoomProperties.getAdmissionTokenExpireSeconds();
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(expireSeconds));
        return new IdempotencyTokenVO(token, expireSeconds);
    }

    public void consumeAdmissionToken(Long userId, Long ticketCategoryId, String token) {
        if (!waitingRoomProperties.isEnabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException("缺少等待室入场资格");
        }
        String key = RedisKeyConstant.waitingRoomAdmissionTokenKey(ticketCategoryId, userId, token);
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BusinessException("等待室入场资格无效或已使用");
        }
    }
}
