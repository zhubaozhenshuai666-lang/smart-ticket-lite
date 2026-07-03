package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.vo.StockAdjustmentRecordVO;
import com.zewbby.smartticket.service.StockAdjustmentService;
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

    public AdminStockAdjustmentController(StockAdjustmentService stockAdjustmentService) {
        this.stockAdjustmentService = stockAdjustmentService;
    }

    @PostMapping
    public ApiResponse<StockAdjustmentRecordVO> createAdjustment(@Valid @RequestBody CreateStockAdjustmentRequest body) {
        return ApiResponse.successZero(stockAdjustmentService.createAdjustment(body));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<StockAdjustmentRecordVO> confirmAdjustment(@PathVariable Long id,
                                                                  @Valid @RequestBody ConfirmStockAdjustmentRequest body) {
        return ApiResponse.successZero(stockAdjustmentService.confirmAdjustment(id, body));
    }

    @GetMapping
    public ApiResponse<List<StockAdjustmentRecordVO>> listAdjustments(@RequestParam(required = false) Integer limit) {
        return ApiResponse.successZero(stockAdjustmentService.listRecent(limit));
    }
}
