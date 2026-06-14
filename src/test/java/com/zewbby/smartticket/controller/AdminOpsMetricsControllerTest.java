package com.zewbby.smartticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.auth.AdminAuthorizationInterceptor;
import com.zewbby.smartticket.auth.JwtAuthenticationInterceptor;
import com.zewbby.smartticket.auth.JwtProperties;
import com.zewbby.smartticket.auth.JwtTokenProvider;
import com.zewbby.smartticket.auth.TokenBlacklistService;
import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.common.GlobalExceptionHandler;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.OpsMetricsSummaryVO;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.ActivityDegradeService;
import com.zewbby.smartticket.service.CapacityAssessmentService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.OrderSubmitMetadataPrewarmService;
import com.zewbby.smartticket.service.WaitingRoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminOpsMetricsControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void metricsSummaryReturnsBusinessAndRiskMetrics() {
        ObservabilityMetricsService observabilityMetricsService = mock(ObservabilityMetricsService.class);
        when(observabilityMetricsService.getSummary()).thenReturn(new OpsMetricsSummaryVO(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        ));
        AdminOpsMetricsController controller = adminOpsMetricsController(observabilityMetricsService);

        var response = controller.metricsSummary();

        assertThat(response.getData().getOrderCreatedCount()).isEqualTo(1);
        assertThat(response.getData().getLocalMessageDeadCount()).isEqualTo(7);
        assertThat(response.getData().getStockCompensationFailedCount()).isEqualTo(10);
    }

    @Test
    void userRoleCannotAccessMetricsSummaryThroughAdminPath() throws Exception {
        ObservabilityMetricsService observabilityMetricsService = mock(ObservabilityMetricsService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        UserMapper userMapper = mock(UserMapper.class);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(jwtProperties(), new ObjectMapper());
        String token = jwtTokenProvider.generateToken(user(1L, "buyer", "USER", "NORMAL"));
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "buyer", "USER", "NORMAL"));

        MockMvc mockMvc = securedMockMvc(observabilityMetricsService, tokenBlacklistService, userMapper, jwtTokenProvider);

        mockMvc.perform(get("/api/admin/ops/metrics-summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(ErrorMessageConstant.NO_ADMIN_PERMISSION));
    }

    @Test
    void adminRoleCanAccessMetricsSummaryThroughAdminPath() throws Exception {
        ObservabilityMetricsService observabilityMetricsService = mock(ObservabilityMetricsService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        UserMapper userMapper = mock(UserMapper.class);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(jwtProperties(), new ObjectMapper());
        String token = jwtTokenProvider.generateToken(user(2L, "admin", "ADMIN", "NORMAL"));
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(userMapper.selectById(2L)).thenReturn(user(2L, "admin", "ADMIN", "NORMAL"));
        when(observabilityMetricsService.getSummary()).thenReturn(new OpsMetricsSummaryVO(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        ));

        MockMvc mockMvc = securedMockMvc(observabilityMetricsService, tokenBlacklistService, userMapper, jwtTokenProvider);

        mockMvc.perform(get("/api/admin/ops/metrics-summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.localMessageDeadCount").value(7))
                .andExpect(jsonPath("$.data.stockCompensationFailedCount").value(10));
    }

    private MockMvc securedMockMvc(ObservabilityMetricsService observabilityMetricsService,
                                  TokenBlacklistService tokenBlacklistService,
                                  UserMapper userMapper,
                                  JwtTokenProvider jwtTokenProvider) {
        /*
         * 这里用 MockMvc 串起 JWT 拦截器和 Admin 拦截器。
         * 直接调用 Controller 只能证明方法返回数据，不能证明 /api/admin/** 对普通 USER 是受保护的。
         */
        return MockMvcBuilders
                .standaloneSetup(adminOpsMetricsController(observabilityMetricsService))
                .addMappedInterceptors(
                        new String[]{"/api/admin/**"},
                        new JwtAuthenticationInterceptor(jwtTokenProvider, tokenBlacklistService),
                        new AdminAuthorizationInterceptor(userMapper)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AdminOpsMetricsController adminOpsMetricsController(ObservabilityMetricsService observabilityMetricsService) {
        return new AdminOpsMetricsController(
                observabilityMetricsService,
                mock(CapacityAssessmentService.class),
                mock(OrderSubmitMetadataPrewarmService.class),
                mock(ActivityDegradeService.class),
                mock(WaitingRoomService.class)
        );
    }

    private UserAccount user(Long id, String username, String roleCode, String status) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setPhone("13800000000");
        user.setPassword("encoded");
        user.setRoleCode(roleCode);
        user.setStatus(status);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("smart-ticket-test-secret-at-least-32-bytes");
        properties.setExpireMinutes(120);
        return properties;
    }
}
