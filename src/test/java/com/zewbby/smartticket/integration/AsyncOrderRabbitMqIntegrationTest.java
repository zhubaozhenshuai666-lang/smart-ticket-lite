package com.zewbby.smartticket.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AdminBusinessService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncOrderRabbitMqIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminBusinessService adminBusinessService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void asyncOrderOutboxPublisherConfirmConsumerAndMysqlStockRunAsRealChain() throws Exception {
        /*
         * 这条测试覆盖真实链路：
         * HTTP 异步下单 -> MySQL ticket_order_request/local_message -> RabbitTemplate 发送真实 RabbitMQ
         * -> @RabbitListener 消费 -> MySQL 创建 ticket_order -> MySQL 库存 available 转 locked。
         * 单元测试能证明消费者方法逻辑，这条集成测试证明 SQL、事务、队列、路由和消息转换真的能串起来。
         */
        adminBusinessService.preheatStock(2L);
        String token = loginAsUser();
        String idempotencyToken = getJson("/api/orders/idempotency-token", token).at("/data/token").asText();

        JsonNode submitResponse = postJson("/api/orders/async", Map.of(
                "showId", 1L,
                "sessionId", 1L,
                "ticketCategoryId", 2L,
                "quantity", 1,
                "idempotencyToken", idempotencyToken
        ), token);
        String requestId = submitResponse.at("/data/requestId").asText();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order_request WHERE request_id = ?",
                String.class,
                requestId
        )).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM local_message WHERE business_key = ?",
                String.class,
                requestId
        )).isEqualTo("INIT");

        publishLocalMessagesOnce();

        waitUntil("local_message Broker Confirm ack", () -> "CONFIRMED".equals(jdbcTemplate.queryForObject(
                "SELECT status FROM local_message WHERE business_key = ?",
                String.class,
                requestId
        )));
        waitUntil("消费者创建订单", () -> "SUCCESS".equals(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order_request WHERE request_id = ?",
                String.class,
                requestId
        )));

        Long orderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM ticket_order_request WHERE request_id = ?",
                Long.class,
                requestId
        );
        assertThat(orderId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?",
                String.class,
                orderId
        )).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_stock FROM ticket_stock WHERE ticket_category_id = 2",
                Integer.class
        )).isEqualTo(999);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked_stock FROM ticket_stock WHERE ticket_category_id = 2",
                Integer.class
        )).isEqualTo(1);

        rabbitTemplate.convertAndSend(
                RabbitMqConstant.ORDER_ASYNC_EXCHANGE,
                RabbitMqConstant.ORDER_ASYNC_ROUTING_KEY,
                new AsyncCreateOrderMessage(requestId, 1L, 1L, 1L, 2L, 1)
        );
        Thread.sleep(500L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ticket_order WHERE ticket_category_id = 2",
                Integer.class
        )).isEqualTo(1);
    }
}
