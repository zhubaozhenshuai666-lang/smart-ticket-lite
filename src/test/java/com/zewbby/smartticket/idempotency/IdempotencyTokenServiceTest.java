package com.zewbby.smartticket.idempotency;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void firstConsumeSucceedsWhenLuaReturnsOne() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);

        service.consumeOrderToken(1L, "idem_token");
    }

    @Test
    void secondConsumeFailsWhenLuaReturnsZero() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        assertThatThrownBy(() -> service.consumeOrderToken(1L, "idem_token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.IDEMPOTENCY_TOKEN_USED);
    }

    @Test
    void missingTokenFailsWhenLuaReturnsZero() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        assertThatThrownBy(() -> service.consumeOrderToken(1L, "missing_token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.IDEMPOTENCY_TOKEN_USED);
    }

    @Test
    void concurrentConsumeOfSameTokenAllowsOnlyOneSuccess() throws Exception {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        AtomicInteger consumeAttempts = new AtomicInteger();
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList()))
                .thenAnswer(invocation -> consumeAttempts.getAndIncrement() == 0 ? 1L : 0L);

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<Boolean>) () -> {
                        try {
                            service.consumeOrderToken(1L, "idem_concurrent");
                            return true;
                        } catch (BusinessException exception) {
                            return false;
                        }
                    })
                    .toList();

            long successCount = executor.invokeAll(tasks)
                    .stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();

            assertThat(successCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void generateOrderTokensCreatesDefaultBatchWhenCountIsMissing() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        var tokens = service.generateOrderTokens(1L, null);

        assertThat(tokens).hasSize(10);
        assertThat(tokens).extracting("token").doesNotHaveDuplicates();
        verify(valueOperations, times(10)).set(anyString(), eq("1"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void generateOrderTokensCapsBatchSize() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        var tokens = service.generateOrderTokens(1L, 120);

        assertThat(tokens).hasSize(100);
        verify(valueOperations, times(100)).set(anyString(), eq("1"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void generateOrderTokensRejectsInvalidBatchSize() {
        IdempotencyTokenService service = new IdempotencyTokenService(stringRedisTemplate);

        assertThatThrownBy(() -> service.generateOrderTokens(1L, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等 token 批量数量必须大于 0");
    }
}
