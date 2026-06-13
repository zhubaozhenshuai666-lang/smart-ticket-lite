package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitingRoomServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private WaitingRoomProperties properties;

    private WaitingRoomService waitingRoomService;

    @BeforeEach
    void setUp() {
        properties = new WaitingRoomProperties();
        waitingRoomService = new WaitingRoomService(stringRedisTemplate, properties);
    }

    @Test
    void issueAdmissionTokenStoresExpiringTokenPerTicketCategoryAndUser() {
        properties.setAdmissionTokenExpireSeconds(90);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        var token = waitingRoomService.issueAdmissionToken(1L, 2L);

        assertThat(token.getToken()).startsWith("admit_");
        assertThat(token.getExpireSeconds()).isEqualTo(90);
        verify(valueOperations).set(anyString(), eq("1"), eq(Duration.ofSeconds(90)));
    }

    @Test
    void issueAdmissionTokensCreatesDefaultBatchByPipeline() {
        properties.setAdmissionTokenExpireSeconds(90);
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

        var tokens = waitingRoomService.issueAdmissionTokens(1L, 2L, null);

        assertThat(tokens).hasSize(10);
        assertThat(tokens).extracting("token").doesNotHaveDuplicates();
        assertThat(tokens).allSatisfy(token -> {
            assertThat(token.getToken()).startsWith("admit_");
            assertThat(token.getExpireSeconds()).isEqualTo(90);
        });
        verify(stringRedisTemplate, times(1)).executePipelined(any(RedisCallback.class));
        verify(valueOperations, never()).set(anyString(), eq("1"), any());
    }

    @Test
    void issueAdmissionTokensCapsBatchSize() {
        properties.setMaxAdmissionTokenBatchSize(20);
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

        var tokens = waitingRoomService.issueAdmissionTokens(1L, 2L, 50);

        assertThat(tokens).hasSize(20);
        verify(stringRedisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    void issueAdmissionTokensRejectsInvalidBatchSize() {
        assertThatThrownBy(() -> waitingRoomService.issueAdmissionTokens(1L, 2L, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等待室入场资格批量数量必须大于 0");
    }

    @Test
    void consumeAdmissionTokenDoesNothingWhenWaitingRoomIsDisabled() {
        waitingRoomService.consumeAdmissionToken(1L, 2L, null);

        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void consumeAdmissionTokenDeletesTokenWhenWaitingRoomIsEnabled() {
        properties.setEnabled(true);
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);

        waitingRoomService.consumeAdmissionToken(1L, 2L, "admit_token");

        verify(stringRedisTemplate).delete(anyString());
    }

    @Test
    void consumeAdmissionTokenRejectsMissingOrUsedToken() {
        properties.setEnabled(true);
        assertThatThrownBy(() -> waitingRoomService.consumeAdmissionToken(1L, 2L, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少等待室入场资格");

        when(stringRedisTemplate.delete(anyString())).thenReturn(false);
        assertThatThrownBy(() -> waitingRoomService.consumeAdmissionToken(1L, 2L, "admit_used"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等待室入场资格无效或已使用");
    }

    @Test
    void enterQueueAddsUserAndReturnsPosition() {
        properties.setEnabled(true);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(zSetOperations.score(anyString(), eq("1"))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(7L);
        when(zSetOperations.rank(anyString(), eq("1"))).thenReturn(0L);
        when(zSetOperations.zCard(anyString())).thenReturn(3L);

        var status = waitingRoomService.enterQueue(1L, 2L);

        assertThat(status.isQueued()).isTrue();
        assertThat(status.getPosition()).isEqualTo(1L);
        assertThat(status.getQueueSize()).isEqualTo(3L);
        verify(zSetOperations).add(anyString(), eq("1"), eq(7D));
    }

    @Test
    void releaseAdmissionBatchIssuesTokensAndRemovesQueuedUsers() {
        properties.setEnabled(true);
        properties.setAdmissionTokenExpireSeconds(90);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(zSetOperations.range(anyString(), eq(0L), eq(1L))).thenReturn(Set.of("1", "2"));

        var grants = waitingRoomService.releaseAdmissionBatch(2L, 2);

        assertThat(grants).hasSize(2);
        assertThat(grants).extracting("userId").containsExactlyInAnyOrder(1L, 2L);
        verify(zSetOperations).remove(anyString(), eq("1"));
        verify(zSetOperations).remove(anyString(), eq("2"));
        verify(valueOperations, times(2)).set(anyString(), eq("1"), eq(Duration.ofSeconds(90)));
    }
}
