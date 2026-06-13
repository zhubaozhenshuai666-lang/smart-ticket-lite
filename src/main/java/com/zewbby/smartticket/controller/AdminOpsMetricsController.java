package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.vo.CapacityAssessmentVO;
import com.zewbby.smartticket.domain.vo.MetadataPrewarmResultVO;
import com.zewbby.smartticket.domain.vo.OpsMetricsSummaryVO;
import com.zewbby.smartticket.domain.vo.WaitingRoomAdmissionGrantVO;
import com.zewbby.smartticket.service.ActivityDegradeService;
import com.zewbby.smartticket.service.CapacityAssessmentService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.OrderSubmitMetadataPrewarmService;
import com.zewbby.smartticket.service.WaitingRoomService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/ops")
public class AdminOpsMetricsController {

    private final ObservabilityMetricsService observabilityMetricsService;

    private final CapacityAssessmentService capacityAssessmentService;

    private final OrderSubmitMetadataPrewarmService orderSubmitMetadataPrewarmService;

    private final ActivityDegradeService activityDegradeService;

    private final WaitingRoomService waitingRoomService;

    public AdminOpsMetricsController(ObservabilityMetricsService observabilityMetricsService,
                                     CapacityAssessmentService capacityAssessmentService,
                                     OrderSubmitMetadataPrewarmService orderSubmitMetadataPrewarmService,
                                     ActivityDegradeService activityDegradeService,
                                     WaitingRoomService waitingRoomService) {
        this.observabilityMetricsService = observabilityMetricsService;
        this.capacityAssessmentService = capacityAssessmentService;
        this.orderSubmitMetadataPrewarmService = orderSubmitMetadataPrewarmService;
        this.activityDegradeService = activityDegradeService;
        this.waitingRoomService = waitingRoomService;
    }

    /**
     * 返回后台运维摘要指标。
     *
     * 这些指标不是为了替代日志，而是为了让运维一眼看到交易系统是否有积压和异常：
     * 消息 DEAD、死信 PENDING、库存差异 PENDING、补偿 FAILED 都应该被持续盯住。
     * 该接口放在 /api/admin 下，普通 USER 不能访问，避免把内部治理状态暴露给购票用户。
     */
    @GetMapping("/metrics-summary")
    public ApiResponse<OpsMetricsSummaryVO> metricsSummary() {
        return ApiResponse.successZero(observabilityMetricsService.getSummary());
    }

    @GetMapping("/capacity/order-pipeline")
    public ApiResponse<CapacityAssessmentVO> orderPipelineCapacity() {
        return ApiResponse.successZero(capacityAssessmentService.assessOrderPipelineCapacity());
    }

    @PostMapping("/metadata-prewarm/order-submit")
    public ApiResponse<MetadataPrewarmResultVO> prewarmOrderSubmitMetadata() {
        return ApiResponse.successZero(orderSubmitMetadataPrewarmService.prewarmOrderSubmitMetadata());
    }

    @PostMapping("/waiting-room/admission-batches")
    public ApiResponse<List<WaitingRoomAdmissionGrantVO>> releaseWaitingRoomAdmissionBatch(@RequestParam Long ticketCategoryId,
                                                                                           @RequestParam(value = "count", required = false) Integer count) {
        return ApiResponse.successZero(waitingRoomService.releaseAdmissionBatch(ticketCategoryId, count));
    }

    @PostMapping("/degrade/order-submit")
    public ApiResponse<Void> closeOrderSubmit(@RequestParam String activityScopeKey,
                                              @RequestParam(defaultValue = "300") long ttlSeconds) {
        activityDegradeService.closeOrderSubmit(activityScopeKey, ttlSeconds);
        return ApiResponse.successZero(null);
    }

    @DeleteMapping("/degrade/order-submit")
    public ApiResponse<Void> openOrderSubmit(@RequestParam String activityScopeKey) {
        activityDegradeService.openOrderSubmit(activityScopeKey);
        return ApiResponse.successZero(null);
    }
}
