package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.UserStatusCacheProperties;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheServiceTest {

    @Mock
    private UserMapper userMapper;

    private UserStatusCacheProperties properties;

    private UserStatusCacheService service;

    @BeforeEach
    void setUp() {
        properties = new UserStatusCacheProperties();
        properties.setEnabled(true);
        properties.setMaximumSize(100);
        properties.setExpireAfterWriteSeconds(60);
        service = new UserStatusCacheService(userMapper, properties);
    }

    @Test
    void normalUserStatusIsCached() {
        when(userMapper.selectById(1L)).thenReturn(user("NORMAL"));

        assertThat(service.isNormalUser(1L)).isTrue();
        assertThat(service.isNormalUser(1L)).isTrue();

        verify(userMapper, times(1)).selectById(1L);
    }

    @Test
    void disabledUserIsRejectedAndCached() {
        when(userMapper.selectById(1L)).thenReturn(user("DISABLED"));

        assertThat(service.isNormalUser(1L)).isFalse();
        assertThat(service.isNormalUser(1L)).isFalse();

        verify(userMapper, times(1)).selectById(1L);
    }

    @Test
    void disabledCacheQueriesEveryTimeWhenCacheIsOff() {
        properties.setEnabled(false);
        when(userMapper.selectById(1L)).thenReturn(user("NORMAL"));

        assertThat(service.isNormalUser(1L)).isTrue();
        assertThat(service.isNormalUser(1L)).isTrue();

        verify(userMapper, times(2)).selectById(1L);
    }

    @Test
    void invalidateForcesReload() {
        when(userMapper.selectById(1L)).thenReturn(user("NORMAL"), user("DISABLED"));

        assertThat(service.isNormalUser(1L)).isTrue();
        service.invalidate(1L);
        assertThat(service.isNormalUser(1L)).isFalse();

        verify(userMapper, times(2)).selectById(1L);
    }

    private UserAccount user(String status) {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setStatus(status);
        return user;
    }
}
