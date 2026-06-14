package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.entity.LocalMessage;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminOperationLogService;
import com.zewbby.smartticket.service.LocalMessageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/local-messages")
public class AdminLocalMessageController {

    private static final int DEFAULT_LIMIT = 50;

    private final LocalMessageService localMessageService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminLocalMessageController(LocalMessageService localMessageService,
                                       AdminOperationLogService adminOperationLogService) {
        this.localMessageService = localMessageService;
        this.adminOperationLogService = adminOperationLogService;
    }

    @GetMapping
    public ApiResponse<List<LocalMessage>> listMessages(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer limit) {
        return ApiResponse.successZero(localMessageService.selectRecent(status, normalizeLimit(limit)));
    }

    @GetMapping("/{messageId}")
    public ApiResponse<LocalMessage> getMessage(@PathVariable String messageId) {
        return ApiResponse.successZero(localMessageService.getByMessageId(messageId));
    }

    /**
     * 人工重试只把消息放回 INIT，不在接口线程里直接发送 MQ。
     *
     * 这样所有自动重试和人工重试都会统一经过发送器、Publisher Confirm、ReturnCallback 和超时扫描，
     * 避免人工接口绕过可靠投递状态机。
     */
    @PostMapping("/{messageId}/retry")
    public ApiResponse<Void> retry(@PathVariable String messageId, HttpServletRequest request) {
        try {
            localMessageService.retryManually(messageId);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.LOCAL_MESSAGE_RETRY,
                    "LOCAL_MESSAGE", messageId, request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.LOCAL_MESSAGE_RETRY,
                    "LOCAL_MESSAGE", messageId, exception, request);
            throw exception;
        }
    }

    @PostMapping("/{messageId}/mark-dead")
    public ApiResponse<Void> markDead(@PathVariable String messageId, HttpServletRequest request) {
        try {
            localMessageService.markDeadManually(messageId);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.LOCAL_MESSAGE_MARK_DEAD,
                    "LOCAL_MESSAGE", messageId, request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.LOCAL_MESSAGE_MARK_DEAD,
                    "LOCAL_MESSAGE", messageId, exception, request);
            throw exception;
        }
    }

    private Integer normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 200);
    }
}
