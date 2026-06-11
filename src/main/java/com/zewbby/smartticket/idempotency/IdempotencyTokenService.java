package com.zewbby.smartticket.idempotency;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.IdempotencyTokenVO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class IdempotencyTokenService {

    private static final long ORDER_TOKEN_EXPIRE_SECONDS = 300L;

    private static final int DEFAULT_BATCH_SIZE = 10;

    private static final int MAX_BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> consumeTokenScript;

    public IdempotencyTokenService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.consumeTokenScript = buildConsumeTokenScript();
    }

    /**
     * 生成订单token
     * @param userId
     * @return
     */
    public IdempotencyTokenVO generateOrderToken(Long userId) {
        //生成token
        String token = generateTokenValue();
        //生成对应幂等token
        String key = RedisKeyConstant.orderIdempotencyTokenKey(userId, token);
        //记录下来对应的token到redis里
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ORDER_TOKEN_EXPIRE_SECONDS));
        return new IdempotencyTokenVO(token, ORDER_TOKEN_EXPIRE_SECONDS);
    }

    /**
     * 批量生成订单幂等 token。
     *
     * 抢票页可以在初始化或进入等待室后一次预取一小池 token，点击下单时直接消费已有 token，
     * 避免高峰瞬间出现“先取 token、再下单”的双倍入口请求。
     */
    public List<IdempotencyTokenVO> generateOrderTokens(Long userId, Integer count) {
        int safeCount = normalizeBatchCount(count);
        List<IdempotencyTokenVO> tokens = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            String token = generateTokenValue();
            tokens.add(new IdempotencyTokenVO(token, ORDER_TOKEN_EXPIRE_SECONDS));
        }
        writeTokensByPipeline(userId, tokens);
        return tokens;
    }

    /**
     * 消费token
     * @param userId
     * @param token
     */
    public void consumeOrderToken(Long userId, String token) {
        String key = RedisKeyConstant.orderIdempotencyTokenKey(userId, token);
        if (!consumeIdempotentToken(key)) {
            throw new BusinessException(ErrorMessageConstant.IDEMPOTENCY_TOKEN_USED);
        }
    }

    private boolean consumeIdempotentToken(String tokenKey) {
        Long result = stringRedisTemplate.execute(
                consumeTokenScript,
                Collections.singletonList(tokenKey)
        );
        return result != null && result == 1L;
    }

    private String generateTokenValue() {
        return "idem_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void writeTokensByPipeline(Long userId, List<IdempotencyTokenVO> tokens) {
        byte[] value = "1".getBytes(StandardCharsets.UTF_8);
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (IdempotencyTokenVO token : tokens) {
                byte[] key = RedisKeyConstant.orderIdempotencyTokenKey(userId, token.getToken())
                        .getBytes(StandardCharsets.UTF_8);
                connection.stringCommands().set(
                        key,
                        value,
                        Expiration.seconds(ORDER_TOKEN_EXPIRE_SECONDS),
                        RedisStringCommands.SetOption.UPSERT
                );
            }
            return null;
        });
    }

    private int normalizeBatchCount(Integer count) {
        if (count == null) {
            return DEFAULT_BATCH_SIZE;
        }
        if (count < 1) {
            throw new BusinessException("幂等 token 批量数量必须大于 0");
        }
        return Math.min(count, MAX_BATCH_SIZE);
    }

    private DefaultRedisScript<Long> buildConsumeTokenScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/idempotency_token_consume.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
