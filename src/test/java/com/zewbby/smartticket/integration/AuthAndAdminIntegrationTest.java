package com.zewbby.smartticket.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAndAdminIntegrationTest extends BaseIntegrationTest {

    @Test
    void registerLoginLogoutAndAdminAuthorizationUseRealMysqlRedisAndInterceptors() throws Exception {
        /*
         * 这条测试不 Mock AuthService、UserMapper 或 Redis 黑名单。
         * 它用真实 MySQL 验证用户落库和 BCrypt 登录，用真实 Redis 验证 logout 黑名单，
         * 再经过真实 MVC 拦截器验证 USER/ADMIN 后台权限边界。
         */
        JsonNode registerResponse = postJson("/api/auth/register", Map.of(
                "username", "it_user",
                "phone", "13900000001",
                "password", "Test123456"
        ), null);
        assertThat(registerResponse.at("/data/phone").asText()).isEqualTo("13900000001");

        JsonNode loginResponse = postJson("/api/auth/login", Map.of(
                "phone", "13900000001",
                "password", "Test123456"
        ), null);
        String userToken = loginResponse.at("/data/token").asText();
        assertThat(userToken).isNotBlank();

        JsonNode meResponse = getJson("/api/users/me", userToken);
        assertThat(meResponse.at("/data/phone").asText()).isEqualTo("13900000001");

        JsonNode userAdminResponse = getJson("/api/admin/stocks", userToken);
        assertThat(userAdminResponse.at("/code").asInt()).isEqualTo(403);

        String adminToken = loginAsAdmin();
        JsonNode adminResponse = getJson("/api/admin/stocks", adminToken);
        assertThat(adminResponse.at("/code").asInt()).isZero();

        JsonNode logoutResponse = postJson("/api/auth/logout", Map.of(), userToken);
        assertThat(logoutResponse.at("/code").asInt()).isEqualTo(200);

        JsonNode afterLogoutResponse = getJson("/api/users/me", userToken);
        assertThat(afterLogoutResponse.at("/code").asInt()).isEqualTo(401);
    }
}
