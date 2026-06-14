package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.vo.StockAdjustmentRecordVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminOperationLogService;
import com.zewbby.smartticket.service.StockAdjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stocks/adjustments")
public class AdminStockAdjustmentController {

    private final StockAdjustmentService stockAdjustmentService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminStockAdjustmentController(StockAdjustmentService stockAdjustmentService,
                                          AdminOperationLogService adminOperationLogService) {
        this.stockAdjustmentService = stockAdjustmentService;
        this.adminOperationLogService = adminOperationLogService;
    }

    @PostMapping
    public ApiResponse<StockAdjustmentRecordVO> createAdjustment(@Valid @RequestBody CreateStockAdjustmentRequest body,
                                                                 HttpServletRequest request) {
        try {
            StockAdjustmentRecordVO result = stockAdjustmentService.createAdjustment(body);
            /*
             * 创建调整申请本身还没有改库存，但它已经生成了可执行的确认凭证。
             * 后台风险控制不能只在最终执行时留痕，否则排查时会看不到是谁发起了这次高风险库存变更。
             */
            adminOperationLogService.recordSuccess(
                    AdminOperationTypeEnum.STOCK_ADJUST,
                    "STOCK_ADJUSTMENT",
                    String.valueOf(result.getId()),
                    request
            );
            return ApiResponse.successZero(result);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(
                    AdminOperationTypeEnum.STOCK_ADJUST,
                    "STOCK_ADJUSTMENT",
                    body == null ? null : String.valueOf(body.getTicketCategoryId()),
                    exception,
                    request
            );
            throw exception;
        }
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<StockAdjustmentRecordVO> confirmAdjustment(@PathVariable Long id,
                                                                  @Valid @RequestBody ConfirmStockAdjustmentRequest body,
                                                                  HttpServletRequest request) {
        try {
            StockAdjustmentRecordVO result = stockAdjustmentService.confirmAdjustment(id, body);
            /*
             * confirm 才是真正改变库存的动作。这里必须写审计，记录操作者、IP、traceId 和资源 ID。
             * 审计日志不能替代业务表，二者职责不同：业务表解释库存怎么变，审计表解释是谁触发了变化。
             */
            adminOperationLogService.recordSuccess(
                    AdminOperationTypeEnum.STOCK_ADJUST,
                    "STOCK_ADJUSTMENT",
                    String.valueOf(id),
                    request
            );
            return ApiResponse.successZero(result);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(
                    AdminOperationTypeEnum.STOCK_ADJUST,
                    "STOCK_ADJUSTMENT",
                    String.valueOf(id),
                    exception,
                    request
            );
            throw exception;
        }
    }

    @GetMapping
    public ApiResponse<List<StockAdjustmentRecordVO>> listAdjustments(@RequestParam(required = false) Integer limit) {
        return ApiResponse.successZero(stockAdjustmentService.listRecent(limit));
    }
}
