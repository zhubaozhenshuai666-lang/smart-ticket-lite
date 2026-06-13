package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.IdempotencyTokenVO;
import com.zewbby.smartticket.domain.vo.WaitingRoomAdmissionGrantVO;
import com.zewbby.smartticket.domain.vo.WaitingRoomStatusVO;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    public WaitingRoomStatusVO enterQueue(Long userId, Long ticketCategoryId) {
        if (!waitingRoomProperties.isEnabled()) {
            return new WaitingRoomStatusVO(ticketCategoryId, userId, false, 0L, 0L);
        }
        String queueKey = RedisKeyConstant.waitingRoomQueueKey(ticketCategoryId);
        String member = String.valueOf(userId);
        Double existingScore = stringRedisTemplate.opsForZSet().score(queueKey, member);
        if (existingScore == null) {
            Long sequence = stringRedisTemplate.opsForValue().increment(RedisKeyConstant.waitingRoomSequenceKey(ticketCategoryId));
            stringRedisTemplate.opsForZSet().add(queueKey, member, sequence == null ? System.currentTimeMillis() : sequence);
        }
        return getQueueStatus(userId, ticketCategoryId);
    }

    public WaitingRoomStatusVO getQueueStatus(Long userId, Long ticketCategoryId) {
        if (!waitingRoomProperties.isEnabled()) {
            return new WaitingRoomStatusVO(ticketCategoryId, userId, false, 0L, 0L);
        }
        String queueKey = RedisKeyConstant.waitingRoomQueueKey(ticketCategoryId);
        Long rank = stringRedisTemplate.opsForZSet().rank(queueKey, String.valueOf(userId));
        Long size = stringRedisTemplate.opsForZSet().zCard(queueKey);
        boolean queued = rank != null;
        return new WaitingRoomStatusVO(
                ticketCategoryId,
                userId,
                queued,
                queued ? rank + 1 : null,
                size == null ? 0L : size
        );
    }

    public List<WaitingRoomAdmissionGrantVO> releaseAdmissionBatch(Long ticketCategoryId, Integer count) {
        int safeCount = normalizeBatchCount(count);
        String queueKey = RedisKeyConstant.waitingRoomQueueKey(ticketCategoryId);
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(queueKey, 0, safeCount - 1L);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<WaitingRoomAdmissionGrantVO> grants = new ArrayList<>(userIds.size());
        for (String userIdValue : userIds) {
            Long userId = parseUserId(userIdValue);
            if (userId == null) {
                stringRedisTemplate.opsForZSet().remove(queueKey, userIdValue);
                continue;
            }
            IdempotencyTokenVO token = issueAdmissionToken(userId, ticketCategoryId);
            stringRedisTemplate.opsForZSet().remove(queueKey, userIdValue);
            grants.add(new WaitingRoomAdmissionGrantVO(userId, token.getToken(), token.getExpireSeconds()));
        }
        return grants;
    }

    /**
     * 给进入等候室的user发token
     * @param userId
     * @param ticketCategoryId
     * @return
     */
    public IdempotencyTokenVO issueAdmissionToken(Long userId, Long ticketCategoryId) {
        long expireSeconds = waitingRoomProperties.getAdmissionTokenExpireSeconds();
        String token = generateAdmissionTokenValue();
        String key = RedisKeyConstant.waitingRoomAdmissionTokenKey(ticketCategoryId, userId, token);
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(expireSeconds));
        return new IdempotencyTokenVO(token, expireSeconds);
    }

    /**
     * 批量发放等待室入场资格。
     *
     * 大促抢票不能让用户点击瞬间再发起“取入场资格 + 下单”两次请求。进入等待室或抢票页时预发一小池
     * admission token，点击时直接消费，可以把入口 HTTP 和 Redis 写压力从秒杀瞬间提前摊平。
     */
    public List<IdempotencyTokenVO> issueAdmissionTokens(Long userId, Long ticketCategoryId, Integer count) {
        int safeCount = normalizeBatchCount(count);
        long expireSeconds = waitingRoomProperties.getAdmissionTokenExpireSeconds();
        List<IdempotencyTokenVO> tokens = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            tokens.add(new IdempotencyTokenVO(generateAdmissionTokenValue(), expireSeconds));
        }
        writeAdmissionTokensByPipeline(userId, ticketCategoryId, tokens, expireSeconds);
        return tokens;
    }

    /**
     * 消耗token
     * @param userId
     * @param ticketCategoryId
     * @param token
     */
    public void consumeAdmissionToken(Long userId, Long ticketCategoryId, String token) {
        if (!waitingRoomProperties.isEnabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException("缺少等待室入场资格");
        }
        String key = RedisKeyConstant.waitingRoomAdmissionTokenKey(ticketCategoryId, userId, token);
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!deleted) {
            throw new BusinessException("等待室入场资格无效或已使用");
        }
    }

    private String generateAdmissionTokenValue() {
        return "admit_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void writeAdmissionTokensByPipeline(Long userId,
                                                Long ticketCategoryId,
                                                List<IdempotencyTokenVO> tokens,
                                                long expireSeconds) {
        byte[] value = "1".getBytes(StandardCharsets.UTF_8);
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (IdempotencyTokenVO token : tokens) {
                byte[] key = RedisKeyConstant.waitingRoomAdmissionTokenKey(ticketCategoryId, userId, token.getToken())
                        .getBytes(StandardCharsets.UTF_8);
                connection.stringCommands().set(
                        key,
                        value,
                        Expiration.seconds(expireSeconds),
                        RedisStringCommands.SetOption.UPSERT
                );
            }
            return null;
        });
    }

    private int normalizeBatchCount(Integer count) {
        if (count == null) {
            return waitingRoomProperties.getDefaultAdmissionTokenBatchSize();
        }
        if (count < 1) {
            throw new BusinessException("等待室入场资格批量数量必须大于 0");
        }
        return Math.min(count, waitingRoomProperties.getMaxAdmissionTokenBatchSize());
    }

    private Long parseUserId(String userIdValue) {
        try {
            return Long.valueOf(userIdValue);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
