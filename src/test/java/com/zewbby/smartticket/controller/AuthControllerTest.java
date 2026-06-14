package com.zewbby.smartticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.auth.JwtAuthenticationInterceptor;
import com.zewbby.smartticket.auth.JwtProperties;
import com.zewbby.smartticket.auth.JwtTokenProvider;
import com.zewbby.smartticket.auth.TokenBlacklistService;
import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.common.GlobalExceptionHandler;
import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.dto.LoginRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.LoginResponseVO;
import com.zewbby.smartticket.domain.vo.UserVO;
import com.zewbby.smartticket.service.AuthService;
import com.zewbby.smartticket.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtTokenProvider = new JwtTokenProvider(jwtProperties(), objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService), new UserController(userService))
                .addMappedInterceptors(
                        new String[]{"/api/users/me"},
                        new JwtAuthenticationInterceptor(jwtTokenProvider, tokenBlacklistService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void registerEndpointReturnsUserWithoutPassword() throws Exception {
        when(authService.register(any(CreateUserRequest.class)))
                .thenReturn(new UserVO(1L, "tester", "13800000000", "NORMAL", "USER", LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateUserRequest("tester", "13800000000", "Test123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("tester"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void loginEndpointReturnsJwtToken() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponseVO(1L, "tester", "13800000000", "USER", "jwt-token", LocalDateTime.now().plusHours(2)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("13800000000", "Test123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.token").value("jwt-token"));
    }

    @Test
    void bearerTokenCanAccessCurrentUserEndpoint() throws Exception {
        String token = jwtTokenProvider.generateToken(user());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(userService.getCurrentUser())
                .thenAnswer(invocation -> new UserVO(UserContext.requireUserId(), "tester", "13800000000",
                        "NORMAL", "USER", LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void logoutEndpointReturnsSuccessWhenBearerTokenIsValid() throws Exception {
        String token = jwtTokenProvider.generateToken(user());
        doNothing().when(authService).logout(anyString());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService), new UserController(userService))
                .addMappedInterceptors(
                        new String[]{"/api/auth/logout"},
                        new JwtAuthenticationInterceptor(jwtTokenProvider, tokenBlacklistService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void logoutEndpointIsIdempotentForBlacklistedToken() throws Exception {
        String token = jwtTokenProvider.generateToken(user());
        doNothing().when(authService).logout(anyString());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService), new UserController(userService))
                .addMappedInterceptors(
                        new String[]{"/api/auth/logout"},
                        new JwtAuthenticationInterceptor(jwtTokenProvider, tokenBlacklistService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void blacklistedTokenCannotAccessCurrentUserEndpoint() throws Exception {
        String token = jwtTokenProvider.generateToken(user());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("token已退出登录"));
    }

    @Test
    void currentUserEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    private UserAccount user() {
        return new UserAccount(1L, "tester", "13800000000", "encoded", "NORMAL", "USER",
                LocalDateTime.now(), LocalDateTime.now());
    }

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("smart-ticket-test-secret-at-least-32-bytes");
        properties.setExpireMinutes(120);
        return properties;
    }
}
