package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.entity.StockConsistencyRecord;
import com.zewbby.smartticket.domain.vo.StockConsistencyVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.AdminOperationLogService;
import com.zewbby.smartticket.service.StockConsistencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stocks")
public class AdminStockController {

    private final StockCacheService stockCacheService;

    private final StockConsistencyService stockConsistencyService;

    private final AdminBusinessService adminBusinessService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminStockController(StockCacheService stockCacheService,
                                StockConsistencyService stockConsistencyService,
                                AdminBusinessService adminBusinessService,
                                AdminOperationLogService adminOperationLogService) {
        this.stockCacheService = stockCacheService;
        this.stockConsistencyService = stockConsistencyService;
        this.adminBusinessService = adminBusinessService;
        this.adminOperationLogService = adminOperationLogService;
    }

    /**
     * 预热所有的库存
     * @return
     */
    @PostMapping("/preload")
    public ApiResponse<Void> preloadAllStock(HttpServletRequest request) {
        try {
            adminBusinessService.preheatAllStock();
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.STOCK_PREHEAT,
                    "TICKET_STOCK", "ALL", request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.STOCK_PREHEAT,
                    "TICKET_STOCK", "ALL", exception, request);
            throw exception;
        }
    }

    /**
     * 预热某些库存
     * @param ticketCategoryId
     * @return
     */
    @PostMapping("/{ticketCategoryId}/preload")
    public ApiResponse<Integer> preloadStock(@PathVariable Long ticketCategoryId, HttpServletRequest request) {
        try {
            Integer stock = adminBusinessService.preheatStock(ticketCategoryId).getRedisAvailableStock();
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.STOCK_PREHEAT,
                    "TICKET_STOCK", String.valueOf(ticketCategoryId), request);
            return ApiResponse.successZero(stock);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.STOCK_PREHEAT,
                    "TICKET_STOCK", String.valueOf(ticketCategoryId), exception, request);
            throw exception;
        }
    }

    /**
     * 查询redis中对应可用库存
     * @param ticketCategoryId
     * @return
     */
    @GetMapping("/{ticketCategoryId}/redis")
    public ApiResponse<Integer> getRedisAvailableStock(@PathVariable Long ticketCategoryId) {
        return ApiResponse.successZero(stockCacheService.getAvailableStock(ticketCategoryId));
    }

    /**
     * 检查库存和缓存的一致性
     * @param ticketCategoryId
     * @return
     */
    @GetMapping("/{ticketCategoryId}/consistency")
    public ApiResponse<StockConsistencyVO> checkStockConsistency(@PathVariable Long ticketCategoryId) {
        return ApiResponse.successZero(stockConsistencyService.checkStockConsistency(ticketCategoryId));
    }

    @PostMapping("/consistency/check/{ticketCategoryId}")
    public ApiResponse<StockConsistencyVO> checkOne(@PathVariable Long ticketCategoryId) {
        return ApiResponse.successZero(stockConsistencyService.checkOne(ticketCategoryId, "MANUAL"));
    }

    @PostMapping("/consistency/check-all")
    public ApiResponse<List<StockConsistencyVO>> checkAll(@RequestParam(required = false) Integer batchSize) {
        return ApiResponse.successZero(stockConsistencyService.checkAll("MANUAL", batchSize));
    }

    @GetMapping("/consistency-records")
    public ApiResponse<List<StockConsistencyRecord>> listConsistencyRecords(@RequestParam(required = false) String status,
                                                                            @RequestParam(required = false) Integer limit) {
        return ApiResponse.successZero(stockConsistencyService.listRecords(status, limit));
    }

    /**
     * 人工触发 Redis 修复。
     *
     * 这个接口不会裸 SET Redis 库存；服务层会重新计算 expectedRedisAvailable，并用 Lua CAS + Delta 修复。
     * 如果 Redis 在检查和修复之间被并发下单修改，Lua 会拒绝修复，要求重新 check。
     */
    @PostMapping("/consistency-records/{id}/repair")
    public ApiResponse<Void> repairConsistencyRecord(@PathVariable Long id, HttpServletRequest request) {
        try {
            stockConsistencyService.repairRecord(id);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.STOCK_REPAIR,
                    "STOCK_CONSISTENCY_RECORD", String.valueOf(id), request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.STOCK_REPAIR,
                    "STOCK_CONSISTENCY_RECORD", String.valueOf(id), exception, request);
            throw exception;
        }
    }

    @PostMapping("/consistency-records/{id}/ignore")
    public ApiResponse<Void> ignoreConsistencyRecord(@PathVariable Long id, HttpServletRequest request) {
        try {
            stockConsistencyService.ignoreRecord(id);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.STOCK_CONSISTENCY_IGNORE,
                    "STOCK_CONSISTENCY_RECORD", String.valueOf(id), request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.STOCK_CONSISTENCY_IGNORE,
                    "STOCK_CONSISTENCY_RECORD", String.valueOf(id), exception, request);
            throw exception;
        }
    }

    @PostMapping("/failed-requests/compensate")
    public ApiResponse<Integer> compensateFailedRequests(@RequestParam(required = false) Integer batchSize,
                                                         HttpServletRequest request) {
        try {
            Integer count = stockConsistencyService.compensateFailedRequests(batchSize);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.ORDER_REQUEST_COMPENSATE,
                    "TICKET_ORDER_REQUEST", "BATCH", request);
            return ApiResponse.successZero(count);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.ORDER_REQUEST_COMPENSATE,
                    "TICKET_ORDER_REQUEST", "BATCH", exception, request);
            throw exception;
        }
    }
}
