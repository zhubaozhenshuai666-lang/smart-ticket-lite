package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.AdminCreateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateShowRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateTicketCategoryRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateShowRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateTicketCategoryRequest;
import com.zewbby.smartticket.domain.entity.PerformanceSession;
import com.zewbby.smartticket.domain.entity.ShowInfo;
import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin")
public class AdminBusinessController {

    private final AdminBusinessService adminBusinessService;

    private final AdminOperationLogService adminOperationLogService;

    public AdminBusinessController(AdminBusinessService adminBusinessService,
                                   AdminOperationLogService adminOperationLogService) {
        this.adminBusinessService = adminBusinessService;
        this.adminOperationLogService = adminOperationLogService;
    }

    @GetMapping("/shows")
    public ApiResponse<List<ShowInfo>> listShows(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer limit) {
        /*
         * 后台查询和用户侧查询必须分开：用户侧只能看到 PUBLISHED，后台要能看到 DRAFT/OFFLINE，
         * 否则运营人员无法检查草稿、排查下架资源，也容易为了“能查到”误开用户侧接口权限。
         */
        return ApiResponse.successZero(adminBusinessService.listShows(status, limit));
    }

    @GetMapping("/shows/{showId}")
    public ApiResponse<ShowInfo> getShow(@PathVariable Long showId) {
        return ApiResponse.successZero(adminBusinessService.getShow(showId));
    }

    @PostMapping("/shows")
    public ApiResponse<ShowInfo> createShow(@Valid @RequestBody AdminCreateShowRequest body,
                                            HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.SHOW_CREATE, "SHOW", null, request,
                () -> adminBusinessService.createShow(body),
                show -> String.valueOf(show.getId()));
    }

    @PutMapping("/shows/{showId}")
    public ApiResponse<ShowInfo> updateShow(@PathVariable Long showId,
                                            @Valid @RequestBody AdminUpdateShowRequest body,
                                            HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.SHOW_UPDATE, "SHOW", String.valueOf(showId), request,
                () -> adminBusinessService.updateShow(showId, body));
    }

    @PostMapping("/shows/{showId}/publish")
    public ApiResponse<Void> publishShow(@PathVariable Long showId, HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.SHOW_PUBLISH, "SHOW", String.valueOf(showId), request,
                () -> adminBusinessService.publishShow(showId));
    }

    @PostMapping("/shows/{showId}/offline")
    public ApiResponse<Void> offlineShow(@PathVariable Long showId, HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.SHOW_OFFLINE, "SHOW", String.valueOf(showId), request,
                () -> adminBusinessService.offlineShow(showId));
    }

    @GetMapping("/shows/{showId}/sessions")
    public ApiResponse<List<PerformanceSession>> listSessions(@PathVariable Long showId) {
        return ApiResponse.successZero(adminBusinessService.listSessions(showId));
    }

    @PostMapping("/shows/{showId}/sessions")
    public ApiResponse<PerformanceSession> createSession(@PathVariable Long showId,
                                                         @Valid @RequestBody AdminCreateSessionRequest body,
                                                         HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.SESSION_CREATE, "SESSION", null, request,
                () -> adminBusinessService.createSession(showId, body),
                session -> String.valueOf(session.getId()));
    }

    @PutMapping("/sessions/{sessionId}")
    public ApiResponse<PerformanceSession> updateSession(@PathVariable Long sessionId,
                                                         @Valid @RequestBody AdminUpdateSessionRequest body,
                                                         HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.SESSION_UPDATE, "SESSION", String.valueOf(sessionId), request,
                () -> adminBusinessService.updateSession(sessionId, body));
    }

    @PostMapping("/sessions/{sessionId}/publish")
    public ApiResponse<Void> publishSession(@PathVariable Long sessionId, HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.SESSION_PUBLISH, "SESSION", String.valueOf(sessionId), request,
                () -> adminBusinessService.publishSession(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/offline")
    public ApiResponse<Void> offlineSession(@PathVariable Long sessionId, HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.SESSION_OFFLINE, "SESSION", String.valueOf(sessionId), request,
                () -> adminBusinessService.offlineSession(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/ticket-categories")
    public ApiResponse<List<TicketCategory>> listTicketCategories(@PathVariable Long sessionId) {
        return ApiResponse.successZero(adminBusinessService.listTicketCategories(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/ticket-categories")
    public ApiResponse<TicketCategory> createTicketCategory(@PathVariable Long sessionId,
                                                            @Valid @RequestBody AdminCreateTicketCategoryRequest body,
                                                            HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.TICKET_CATEGORY_CREATE, "TICKET_CATEGORY", null, request,
                () -> adminBusinessService.createTicketCategory(sessionId, body),
                ticketCategory -> String.valueOf(ticketCategory.getId()));
    }

    @PutMapping("/ticket-categories/{ticketCategoryId}")
    public ApiResponse<TicketCategory> updateTicketCategory(@PathVariable Long ticketCategoryId,
                                                            @Valid @RequestBody AdminUpdateTicketCategoryRequest body,
                                                            HttpServletRequest request) {
        return audited(AdminOperationTypeEnum.TICKET_CATEGORY_UPDATE, "TICKET_CATEGORY",
                String.valueOf(ticketCategoryId), request,
                () -> adminBusinessService.updateTicketCategory(ticketCategoryId, body));
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/publish")
    public ApiResponse<Void> publishTicketCategory(@PathVariable Long ticketCategoryId,
                                                   HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.TICKET_CATEGORY_PUBLISH, "TICKET_CATEGORY",
                String.valueOf(ticketCategoryId), request,
                () -> adminBusinessService.publishTicketCategory(ticketCategoryId));
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/offline")
    public ApiResponse<Void> offlineTicketCategory(@PathVariable Long ticketCategoryId,
                                                   HttpServletRequest request) {
        return auditedVoid(AdminOperationTypeEnum.TICKET_CATEGORY_OFFLINE, "TICKET_CATEGORY",
                String.valueOf(ticketCategoryId), request,
                () -> adminBusinessService.offlineTicketCategory(ticketCategoryId));
    }

    private <T> ApiResponse<T> audited(AdminOperationTypeEnum operationType,
                                       String resourceType,
                                       String resourceId,
                                       HttpServletRequest request,
                                       Supplier<T> action) {
        return audited(operationType, resourceType, resourceId, request, action, ignored -> resourceId);
    }

    private <T> ApiResponse<T> audited(AdminOperationTypeEnum operationType,
                                       String resourceType,
                                       String fallbackResourceId,
                                       HttpServletRequest request,
                                       Supplier<T> action,
                                       Function<T, String> successResourceIdResolver) {
        try {
            T result = action.get();
            String resourceId = successResourceIdResolver == null ? fallbackResourceId : successResourceIdResolver.apply(result);
            adminOperationLogService.recordSuccess(operationType, resourceType, resourceId, request);
            return ApiResponse.successZero(result);
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(operationType, resourceType, fallbackResourceId, exception, request);
            throw exception;
        }
    }

    private ApiResponse<Void> auditedVoid(AdminOperationTypeEnum operationType,
                                          String resourceType,
                                          String resourceId,
                                          HttpServletRequest request,
                                          Runnable action) {
        try {
            action.run();
            adminOperationLogService.recordSuccess(operationType, resourceType, resourceId, request);
            return ApiResponse.success();
        } catch (RuntimeException exception) {
            adminOperationLogService.recordFailure(operationType, resourceType, resourceId, exception, request);
            throw exception;
        }
    }
}
