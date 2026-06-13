package com.zewbby.smartticket.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityDegradeServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ActivityDegradeService activityDegradeService;

    @BeforeEach
    void setUp() {
        activityDegradeService = new ActivityDegradeService(stringRedisTemplate);
    }

    @Test
    void closeOrderSubmitStoresTtlSwitch() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        activityDegradeService.closeOrderSubmit("show:1:session:1", 300);

        verify(valueOperations).set(anyString(), eq("1"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void openOrderSubmitDeletesSwitch() {
        activityDegradeService.openOrderSubmit("show:1:session:1");

        verify(stringRedisTemplate).delete(anyString());
    }

    @Test
    void isOrderSubmitClosedReadsSwitch() {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);

        assertThat(activityDegradeService.isOrderSubmitClosed("show:1:session:1")).isTrue();
    }
}
