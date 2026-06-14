package com.zewbby.smartticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.auth.JwtProperties;
import com.zewbby.smartticket.auth.JwtTokenProvider;
import com.zewbby.smartticket.auth.LoginFailureService;
import com.zewbby.smartticket.auth.TokenBlacklistService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.dto.LoginRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private LoginFailureService loginFailureService;

    private PasswordEncoder passwordEncoder;

    private JwtTokenProvider jwtTokenProvider;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        jwtTokenProvider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());
        authService = new AuthServiceImpl(userMapper, passwordEncoder, jwtTokenProvider, tokenBlacklistService, loginFailureService);
    }

    @Test
    void registerCreatesUserWithEncryptedPassword() {
        when(userMapper.selectByPhone("13800000000")).thenReturn(null);
        when(userMapper.selectByUsername("test_user")).thenReturn(null);
        when(userMapper.insert(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId(10L);
            return 1;
        });

        var response = authService.register(new CreateUserRequest("test_user", "13800000000", "Test123456"));

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getUsername()).isEqualTo("test_user");
        assertThat(response.getPhone()).isEqualTo("13800000000");
        assertThat(response.getStatus()).isEqualTo("NORMAL");
        assertThat(response.getRoleCode()).isEqualTo("USER");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userMapper).insert(userCaptor.capture());
        UserAccount savedUser = userCaptor.getValue();
        assertThat(savedUser.getPassword()).isNotEqualTo("Test123456");
        assertThat(passwordEncoder.matches("Test123456", savedUser.getPassword())).isTrue();
        assertThat(savedUser.getRoleCode()).isEqualTo("USER");
    }

    @Test
    void registerRejectsDuplicatedPhone() {
        when(userMapper.selectByPhone("13800000000")).thenReturn(existingUser());

        assertThatThrownBy(() -> authService.register(new CreateUserRequest("test_user", "13800000000", "Test123456")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PHONE_EXISTS);
    }

    @Test
    void registerRejectsWeakPassword() {
        assertThatThrownBy(() -> authService.register(new CreateUserRequest("test_user", "13800000000", "password")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PASSWORD_WEAK);
    }

    @Test
    void registerRejectsPasswordSameAsPhone() {
        assertThatThrownBy(() -> authService.register(new CreateUserRequest("test_user", "13800000000", "13800000000")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PASSWORD_WEAK);
    }

    @Test
    void registerRejectsPasswordSameAsUsername() {
        assertThatThrownBy(() -> authService.register(new CreateUserRequest("testuser1", "13800000000", "testuser1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PASSWORD_WEAK);
    }

    @Test
    void loginReturnsJwtTokenWhenPasswordIsCorrect() {
        UserAccount user = existingUser();
        user.setPassword(passwordEncoder.encode("Test123456"));
        when(userMapper.selectByPhone("13800000000")).thenReturn(user);

        var response = authService.login(new LoginRequest("13800000000", "Test123456"));

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getRoleCode()).isEqualTo("USER");
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getExpireAt()).isAfter(LocalDateTime.now());
        assertThat(jwtTokenProvider.parseToken(response.getToken()).getUserId()).isEqualTo(1L);
        assertThat(jwtTokenProvider.parseToken(response.getToken()).getRoleCode()).isEqualTo("USER");
        verify(loginFailureService).checkLoginAllowed("13800000000");
        verify(loginFailureService).clearFailure("13800000000");
    }

    @Test
    void loginRejectsWrongPassword() {
        UserAccount user = existingUser();
        user.setPassword(passwordEncoder.encode("Test123456"));
        when(userMapper.selectByPhone("13800000000")).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest("13800000000", "wrong_password")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ACCOUNT_OR_PASSWORD_ERROR);
        verify(loginFailureService).recordFailure("13800000000");
        verify(loginFailureService, never()).clearFailure("13800000000");
    }

    @Test
    void disabledUserCannotLogin() {
        UserAccount user = existingUser();
        user.setStatus("DISABLED");
        when(userMapper.selectByPhone("13800000000")).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest("13800000000", "Test123456")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void logoutBlacklistsCurrentToken() {
        String token = jwtTokenProvider.generateToken(existingUser());

        authService.logout("Bearer " + token);

        verify(tokenBlacklistService).blacklist(any());
    }

    private UserAccount existingUser() {
        return new UserAccount(
                1L,
                "test_user",
                "13800000000",
                passwordEncoder == null ? "encoded" : passwordEncoder.encode("Test123456"),
                "NORMAL",
                "USER",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private JwtProperties jwtProperties(long expireMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("smart-ticket-test-secret-at-least-32-bytes");
        properties.setExpireMinutes(expireMinutes);
        return properties;
    }
}
