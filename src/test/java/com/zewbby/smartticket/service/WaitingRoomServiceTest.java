package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.WaitingRoomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitingRoomServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

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
}
