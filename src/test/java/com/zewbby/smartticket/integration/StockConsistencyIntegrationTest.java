package com.zewbby.smartticket.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.StockConsistencyVO;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.StockConsistencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockConsistencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminBusinessService adminBusinessService;

    @Autowired
    private StockConsistencyService stockConsistencyService;

    @Autowired
    private OrderRequestMapper orderRequestMapper;

    @Test
    void stockConsistencyUnderstandsInFlightPreDeductRepairAndFailedRequestCompensation() throws Exception {
        /*
         * 这条测试专门验证“不能简单 Redis == MySQL”。
         * 异步请求 QUEUED 时，Redis 已经扣 1 张，但 MySQL available_stock 还没扣，这是正常在途预扣。
         * 只有 Redis 不等于 expectedRedisAvailable 时才应该记录差异并允许 CAS 修复。
         */
        adminBusinessService.preheatStock(2L);
        String token = loginAsUser();
        String idemToken = getJson("/api/orders/idempotency-token", token).at("/data/token").asText();

        JsonNode submitResponse = postJson("/api/orders/async", Map.of(
                "showId", 1L,
                "sessionId", 1L,
                "ticketCategoryId", 2L,
                "quantity", 1,
                "idempotencyToken", idemToken
        ), token);
        String requestId = submitResponse.at("/data/requestId").asText();

        StockConsistencyVO inFlightCheck = stockConsistencyService.checkOne(2L, "MANUAL");
        assertThat(inFlightCheck.getMysqlAvailableStock()).isEqualTo(1000);
        assertThat(inFlightCheck.getRedisAvailableStock()).isEqualTo(999);
        assertThat(inFlightCheck.getInFlightDeductedQuantity()).isEqualTo(1);
        assertThat(inFlightCheck.getExpectedRedisAvailableStock()).isEqualTo(999);
        assertThat(inFlightCheck.getConsistencyRecordId()).isNull();

        stringRedisTemplate.opsForValue().set(RedisKeyConstant.stockAvailableKey(2L), "900");
        StockConsistencyVO mismatch = stockConsistencyService.checkOne(2L, "MANUAL");
        assertThat(mismatch.getConsistencyRecordId()).isNotNull();
        assertThat(mismatch.getRedisExpectedConsistent()).isFalse();

        stockConsistencyService.repairRecord(mismatch.getConsistencyRecordId());
        assertThat(stringRedisTemplate.opsForValue().get(RedisKeyConstant.stockAvailableKey(2L))).isEqualTo("999");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM stock_consistency_record WHERE id = ?",
                String.class,
                mismatch.getConsistencyRecordId()
        )).isEqualTo("REPAIRED");

        Long requestDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ticket_order_request WHERE request_id = ?",
                Long.class,
                requestId
        );
        assertThat(orderRequestMapper.markFailed(requestDbId, "integration failure")).isEqualTo(1);

        int compensated = stockConsistencyService.compensateFailedRequests(10);
        assertThat(compensated).isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue().get(RedisKeyConstant.stockAvailableKey(2L))).isEqualTo("1000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order_request WHERE request_id = ?",
                String.class,
                requestId
        )).isEqualTo("COMPENSATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM stock_compensation_record",
                Integer.class
        )).isGreaterThanOrEqualTo(2);
    }
}
