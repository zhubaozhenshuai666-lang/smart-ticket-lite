package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncOrderInFlightServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private AsyncOrderSubmitProperties properties;

    private AsyncOrderInFlightService service;

    @BeforeEach
    void setUp() {
        properties = new AsyncOrderSubmitProperties();
        properties.setMaxInFlightPerTicketCategory(2);
        properties.setInFlightCounterTtlSeconds(120);
        service = new AsyncOrderInFlightService(stringRedisTemplate, properties);
    }

    @Test
    void tryAcquireAllowsWhenLuaReturnsPositiveValue() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        assertThat(service.tryAcquire(2L)).isTrue();
    }

    @Test
    void tryAcquireRejectsWhenLuaReturnsZero() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        assertThat(service.tryAcquire(2L)).isFalse();
    }

    @Test
    void disabledControlBypassesRedis() {
        properties.setInFlightControlEnabled(false);

        assertThat(service.tryAcquire(2L)).isTrue();
        service.release(2L);

        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList());
    }

    @Test
    void releaseExecutesLuaWhenEnabled() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        service.release(2L);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList());
    }

    @Test
    void scopedAcquireUsesActivityTicketKeyAndCustomLimit() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("100"), anyString()))
                .thenReturn(1L);

        assertThat(service.tryAcquire("show:1:session:2", 3L, 100L)).isTrue();

        ArgumentCaptor<List<String>> keysCaptor = forClass(List.class);
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq("100"), anyString());
        assertThat(keysCaptor.getValue()).containsExactly("order:async:inflight:activity:show:1:session:2:ticket:3");
    }

    @Test
    void scopedReleaseOnlyReleasesActivityTicketKey() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        service.release("show:1:session:2", 3L);

        ArgumentCaptor<List<String>> keysCaptor = forClass(List.class);
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("order:async:inflight:activity:show:1:session:2:ticket:3");
    }
}
