package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.RiskControlProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskControlServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RiskControlProperties properties;

    private RiskControlService riskControlService;

    @BeforeEach
    void setUp() {
        properties = new RiskControlProperties();
        riskControlService = new RiskControlService(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void allowOrderSubmitSetsTtlOnFirstUserAndIpAttempt() {
        properties.setCounterTtlSeconds(90);
        when(valueOperations.increment(anyString())).thenReturn(1L, 1L);

        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1");

        assertThat(allowed).isTrue();
        verify(stringRedisTemplate, times(2)).expire(anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(90)));
    }

    @Test
    void allowOrderSubmitRejectsUserWhenMinuteLimitExceeded() {
        properties.setMaxUserAttemptsPerMinute(3);
        when(valueOperations.increment(anyString())).thenReturn(4L);

        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1");

        assertThat(allowed).isFalse();
    }

    @Test
    void allowOrderSubmitRejectsImmediatelyWhenGatewayDecisionRejects() {
        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1", "reject");

        assertThat(allowed).isFalse();
    }

    @Test
    void allowOrderSubmitCanSkipLocalCounterWhenGatewayPasses() {
        properties.setSkipLocalCounterWhenGatewayPass(true);

        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1", "pass");

        assertThat(allowed).isTrue();
    }

    @Test
    void allowOrderSubmitRejectsIpWhenMinuteLimitExceeded() {
        properties.setMaxIpAttemptsPerMinute(10);
        when(valueOperations.increment(anyString())).thenReturn(1L, 11L);

        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1");

        assertThat(allowed).isFalse();
    }

    @Test
    void allowOrderSubmitFailOpenWhenRedisThrows() {
        when(valueOperations.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        boolean allowed = riskControlService.allowOrderSubmit(1L, "10.0.0.1");

        assertThat(allowed).isTrue();
    }
}
