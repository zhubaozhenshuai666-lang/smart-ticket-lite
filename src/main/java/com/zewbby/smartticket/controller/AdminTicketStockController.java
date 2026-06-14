package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.dto.AdjustStockRequest;
import com.zewbby.smartticket.domain.dto.InitStockRequest;
import com.zewbby.smartticket.domain.vo.AdminStockVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin")
public class AdminTicketStockController {

    private final AdminBusinessService adminBusinessService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminTicketStockController(AdminBusinessService adminBusinessService,
                                      AdminOperationLogService adminOperationLogService) {
        this.adminBusinessService = adminBusinessService;
        this.adminOperationLogService = adminOperationLogService;
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/stock/init")
    public ApiResponse<AdminStockVO> initStock(@PathVariable Long ticketCategoryId,
                                               @Valid @RequestBody InitStockRequest body,
                                               HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.STOCK_INIT, "TICKET_STOCK", String.valueOf(ticketCategoryId), request,
                () -> adminBusinessService.initStock(ticketCategoryId, body));
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/stock/adjust")
    @Deprecated
    public ApiResponse<AdminStockVO> adjustStock(@PathVariable Long ticketCategoryId,
                                                 @Valid @RequestBody AdjustStockRequest body,
                                                 HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.STOCK_ADJUST, "TICKET_STOCK", String.valueOf(ticketCategoryId), request,
                () -> {
                    /*
                     * 旧接口曾经一步到位修改库存。阶段 4F 后它只能作为兼容入口保留，不能继续执行高风险操作。
                     * ADMIN 权限只证明“这个人能进后台”，不证明“这次库存调整一定正确”；真正调整必须先创建
                     * stock_adjustment_record，再用 confirmToken 二次确认，才能留下 before/after 和回滚建议记录。
                     */
                    throw new BusinessException("库存调整必须先创建调整申请并二次确认");
                });
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/stock/preheat")
    public ApiResponse<AdminStockVO> preheatStock(@PathVariable Long ticketCategoryId,
                                                  HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.STOCK_PREHEAT, "TICKET_STOCK", String.valueOf(ticketCategoryId), request,
                () -> adminBusinessService.preheatStock(ticketCategoryId));
    }

    @PostMapping("/stocks/preheat-all")
    public ApiResponse<List<AdminStockVO>> preheatAllStock(HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.STOCK_PREHEAT, "TICKET_STOCK", "ALL", request,
                adminBusinessService::preheatAllStock);
    }

    @GetMapping("/ticket-categories/{ticketCategoryId}/stock")
    public ApiResponse<AdminStockVO> getStock(@PathVariable Long ticketCategoryId) {
        return ApiResponse.successZero(adminBusinessService.getStock(ticketCategoryId));
    }

    @GetMapping("/stocks")
    public ApiResponse<List<AdminStockVO>> listStocks() {
        return ApiResponse.successZero(adminBusinessService.listStocks());
    }

    private <T> ApiResponse<T> audited(AdminOperationTypeEnum operationType,
                                       String resourceType,
                                       String resourceId,
                                       HttpServletRequest request,
                                       Supplier<T> action) {
        try {
            T result = action.get();
            /*
             * 库存初始化、调整和预热都会改变售卖入口看到的库存事实。
             * 审计日志不是装饰品：它是出问题后判断“谁在什么时间改了哪个票档库存”的唯一后台证据。
             */
            adminOperationLogService.recordSuccess(operationType, resourceType, resourceId, request);
            return ApiResponse.successZero(result);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(operationType, resourceType, resourceId, exception, request);
            throw exception;
        }
    }
}
