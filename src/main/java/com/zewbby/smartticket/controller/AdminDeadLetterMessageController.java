package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.entity.DeadLetterMessage;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminOperationLogService;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/dead-letters")
public class AdminDeadLetterMessageController {

    private final DeadLetterMessageService deadLetterMessageService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminDeadLetterMessageController(DeadLetterMessageService deadLetterMessageService,
                                            AdminOperationLogService adminOperationLogService) {
        this.deadLetterMessageService = deadLetterMessageService;
        this.adminOperationLogService = adminOperationLogService;
    }

    @GetMapping
    public ApiResponse<List<DeadLetterMessage>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer limit) {
        return ApiResponse.successZero(deadLetterMessageService.selectRecent(status, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeadLetterMessage> get(@PathVariable Long id) {
        return ApiResponse.successZero(deadLetterMessageService.getById(id));
    }

    /**
     * 人工 retry 不是随便重投消息。
     *
     * 重试前服务层会检查 request 是否已经成功、是否已经补偿 Redis 库存、是否仍持有预扣语义；
     * 通过后也只是重新写 local_message，让可靠消息发送器统一投递，避免人工接口绕过 Outbox 状态机。
     */
    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable Long id, HttpServletRequest request) {
        try {
            deadLetterMessageService.retry(id);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.DEAD_LETTER_RETRY,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.DEAD_LETTER_RETRY,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), exception, request);
            throw exception;
        }
    }

    @PostMapping("/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable Long id, HttpServletRequest request) {
        try {
            deadLetterMessageService.ignore(id);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.DEAD_LETTER_IGNORE,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.DEAD_LETTER_IGNORE,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), exception, request);
            throw exception;
        }
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolve(@PathVariable Long id, HttpServletRequest request) {
        try {
            deadLetterMessageService.resolve(id);
            adminOperationLogService.recordSuccess(AdminOperationTypeEnum.DEAD_LETTER_RESOLVE,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(AdminOperationTypeEnum.DEAD_LETTER_RESOLVE,
                    "DEAD_LETTER_MESSAGE", String.valueOf(id), exception, request);
            throw exception;
        }
    }
}
