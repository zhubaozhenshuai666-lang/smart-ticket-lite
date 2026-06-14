package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationInterceptorTest {

    @Mock
    private UserMapper userMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void userRoleCannotAccessAdminApi() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(1L, "user", "USER");
        when(userMapper.selectById(1L)).thenReturn(user("USER", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/ops/metrics-summary");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.NO_ADMIN_PERMISSION);
    }

    @Test
    void adminRoleCanAccessHighRiskAdminOperation() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(2L, "admin", "ADMIN");
        when(userMapper.selectById(2L)).thenReturn(user("ADMIN", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/local-messages/MSG1/retry");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void operatorRoleCanAccessQueryApi() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(3L, "operator", "OPERATOR");
        when(userMapper.selectById(3L)).thenReturn(user("OPERATOR", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dead-letters");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void operatorRoleCannotExecuteAdminOnlyOperation() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(3L, "operator", "OPERATOR");
        when(userMapper.selectById(3L)).thenReturn(user("OPERATOR", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/stocks/failed-requests/compensate");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.NO_ADMIN_PERMISSION);
    }

    @Test
    void operatorRoleCanExecuteLowRiskStockPreheat() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(3L, "operator", "OPERATOR");
        when(userMapper.selectById(3L)).thenReturn(user("OPERATOR", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/admin/ticket-categories/2/stock/preheat"
        );

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void operatorRoleCannotInitializeOrAdjustStock() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(3L, "operator", "OPERATOR");
        when(userMapper.selectById(3L)).thenReturn(user("OPERATOR", "NORMAL"));

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/admin/ticket-categories/2/stock/adjust"
        );

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.NO_ADMIN_PERMISSION);
    }

    @Test
    void missingLoginCannotAccessAdminApi() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/local-messages");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.UNAUTHORIZED);
    }

    @Test
    void disabledUserCannotAccessAdminApiEvenWithAdminRole() {
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(userMapper);
        UserContext.setUser(2L, "admin", "ADMIN");
        when(userMapper.selectById(2L)).thenReturn(user("ADMIN", "DISABLED"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/local-messages");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ACCOUNT_UNAVAILABLE);
    }

    private UserAccount user(String roleCode, String status) {
        UserAccount user = new UserAccount();
        user.setId(UserContext.getUserId());
        user.setUsername(UserContext.getUsername());
        user.setPhone("13800000000");
        user.setPassword("encoded");
        user.setStatus(status);
        user.setRoleCode(roleCode);
        return user;
    }
}
