package com.zewbby.smartticket.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.LocalMessageProperties;
import com.zewbby.smartticket.service.PaymentSignatureService;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = {"file:docs/sql/schema.sql", "file:docs/sql/data.sql"})
public abstract class BaseIntegrationTest {

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("smart_ticket_lite")
            .withUsername("smart_ticket")
            .withPassword("smart_ticket");

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(6379);

    @Container
    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @Autowired
    protected LocalMessageProperties localMessageProperties;

    @Autowired
    protected LocalMessagePublishTask localMessagePublishTask;

    @Autowired
    protected PaymentSignatureService paymentSignatureService;

    /**
     * 把 Spring Boot 测试上下文的基础设施连接切到 Testcontainers。
     *
     * 交易系统不能只靠 Mock 测试：Mock 能验证“我期望 mapper 被调用”，但验证不了 SQL 真的能跑、
     * Redis Lua 真的原子执行。
     * Testcontainers 用真实 MySQL/Redis 容器替代本机服务，让集成测试覆盖协议、驱动和 SQL。
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> 0);
    }

    /**
     * 每个测试方法都会重新执行 schema.sql/data.sql，保证 MySQL 数据隔离。
     * Redis 不受 @Sql 管理，所以测试结束后主动 flush，避免上一个测试残留的库存 key、
     * soldout 标记污染下一条链路。
     */
    @AfterEach
    void cleanInfrastructureState() {
        localMessageProperties.setSenderEnabled(false);
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    protected String loginAsUser() throws Exception {
        return login("13800000001", "Test123456");
    }

    protected String loginAsAdmin() throws Exception {
        return login("13800000002", "Test123456");
    }

    protected String login(String phone, String password) throws Exception {
        JsonNode response = postJson("/api/auth/login", Map.of(
                "phone", phone,
                "password", password
        ), null);
        return response.at("/data/token").asText();
    }

    protected JsonNode getJson(String url, String bearerToken) throws Exception {
        MvcResult result = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode postJson(String url, Object body, String bearerToken) throws Exception {
        var builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected void publishLocalMessagesOnce() {
        localMessageProperties.setSenderEnabled(true);
        localMessagePublishTask.publishPendingMessages();
        localMessageProperties.setSenderEnabled(false);
    }

    protected void waitUntil(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断: " + description, exception);
            }
        }
        throw new AssertionError("等待超时: " + description);
    }

    protected Long submitAsyncOrderAndWaitSuccess(String bearerToken, Long ticketCategoryId) throws Exception {
        JsonNode tokenResponse = getJson("/api/orders/idempotency-token", bearerToken);
        String idempotencyToken = tokenResponse.at("/data/token").asText();

        JsonNode submitResponse = postJson("/api/orders/async", Map.of(
                "showId", 1L,
                "sessionId", 1L,
                "ticketCategoryId", ticketCategoryId,
                "quantity", 1,
                "idempotencyToken", idempotencyToken
        ), bearerToken);
        String requestId = submitResponse.at("/data/requestId").asText();

        publishLocalMessagesOnce();
        waitUntil("异步下单请求变为 SUCCESS", () -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM ticket_order_request WHERE request_id = ?",
                    String.class,
                    requestId
            );
            return "SUCCESS".equals(status);
        });
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM ticket_order_request WHERE request_id = ?",
                Long.class,
                requestId
        );
    }

    protected Map<String, Object> mockPaymentBody(String paymentNo, boolean success) {
        Long timestamp = System.currentTimeMillis();
        String nonce = "it-" + UUID.randomUUID();
        String signature = paymentSignatureService.sign(paymentNo, success, timestamp, nonce);
        return Map.of(
                "paymentNo", paymentNo,
                "success", success,
                "timestamp", timestamp,
                "nonce", nonce,
                "signature", signature
        );
    }
}
