package com.zewbby.smartticket.ratelimit;

import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.enums.LocalMessageStatusEnum;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObservabilityMetricsService observabilityMetricsService;

    @Mock
    private LocalMessageService localMessageService;

    private RateLimitProperties properties;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        rateLimitService = new RateLimitService(stringRedisTemplate, properties, observabilityMetricsService, localMessageService);
    }

    @Test
    void tokenBucketAllowsWhenLuaReturnsAllowed() {
        mockTokenBucketResult(1L);

        boolean allowed = rateLimitService.tryAcquireTokenBucket("rate:limit:test", 10, 2D, 1, 120);

        assertThat(allowed).isTrue();
    }

    @Test
    void tokenBucketRejectsWhenLuaReturnsRejected() {
        mockTokenBucketResult(0L);

        boolean allowed = rateLimitService.tryAcquireTokenBucket("rate:limit:test", 10, 2D, 1, 120);

        assertThat(allowed).isFalse();
        verify(observabilityMetricsService).recordRateLimitRejected();
    }

    @Test
    void disabledRateLimitAlwaysAllowsAndDoesNotCallRedis() {
        properties.setEnabled(false);

        boolean allowed = rateLimitService.tryAcquireOrderSubmit(1L, "127.0.0.1", "orders:async", 2L, true);

        assertThat(allowed).isTrue();
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void orderSubmitChecksUserIpApiAndTicketBuckets() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean allowed = rateLimitService.tryAcquireOrderSubmit(1L, "10.0.0.1", "orders:async", 2L, true);

        assertThat(allowed).isTrue();
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keyCaptor.capture(),
                any(Object[].class)
        );
        assertThat(keyCaptor.getValue())
                .containsExactly(
                        "rate:limit:user:1:order",
                        "rate:limit:ip:10.0.0.1:order",
                        "rate:limit:api:orders:async",
                        "rate:limit:ticket:2"
                );
    }

    @Test
    void backpressureRejectsBeforeRedisTokenBucketsWhenLocalMessageBacklogIsHigh() {
        properties.setBackpressureEnabled(true);
        properties.setLocalMessageBacklogRejectThreshold(10);
        when(localMessageService.countByStatus(LocalMessageStatusEnum.INIT.getCode())).thenReturn(11L);
        when(localMessageService.countByStatus(LocalMessageStatusEnum.FAILED.getCode())).thenReturn(0L);
        when(localMessageService.countByStatus(LocalMessageStatusEnum.SENDING.getCode())).thenReturn(0L);
        when(localMessageService.countByStatus(LocalMessageStatusEnum.SENT.getCode())).thenReturn(0L);

        boolean allowed = rateLimitService.tryAcquireOrderSubmit(1L, "10.0.0.1", "orders:async", 2L, true);

        assertThat(allowed).isFalse();
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        verify(observabilityMetricsService).recordRateLimitRejected();
    }

    @Test
    void multiBucketRejectsOrderSubmitWhenAnyDimensionRejects() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean allowed = rateLimitService.tryAcquireOrderSubmit(1L, "10.0.0.1", "orders:async", 2L, true);

        assertThat(allowed).isFalse();
        verify(observabilityMetricsService).recordRateLimitRejected();
    }

    @Test
    void orderSubmitCanSkipTicketDimension() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean allowed = rateLimitService.tryAcquireOrderSubmit(1L, "10.0.0.1", "orders:async", null, false);

        assertThat(allowed).isTrue();
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keyCaptor.capture(),
                any(Object[].class)
        );
        assertThat(keyCaptor.getValue()).containsExactly(
                "rate:limit:user:1:order",
                "rate:limit:ip:10.0.0.1:order",
                "rate:limit:api:orders:async"
        );
    }

    @Test
    void activityAndTicketLimitUsesOneMultiBucketScriptCall() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean allowed = rateLimitService.tryAcquireOrderActivityAndTicket("show:1:session:2", 3L);

        assertThat(allowed).isTrue();
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keyCaptor.capture(),
                any(Object[].class)
        );
        assertThat(keyCaptor.getValue()).containsExactly(
                "rate:limit:activity:show:1:session:2",
                "rate:limit:ticket:3"
        );
    }

    private void mockTokenBucketResult(Long result) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }
}
