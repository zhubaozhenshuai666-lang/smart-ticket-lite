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
import com.zewbby.smartticket.service.AdminBusinessService;
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

@RestController
@RequestMapping("/api/admin")
public class AdminBusinessController {

    private final AdminBusinessService adminBusinessService;

    public AdminBusinessController(AdminBusinessService adminBusinessService) {
        this.adminBusinessService = adminBusinessService;
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
    public ApiResponse<ShowInfo> createShow(@Valid @RequestBody AdminCreateShowRequest body) {
        return ApiResponse.successZero(adminBusinessService.createShow(body));
    }

    @PutMapping("/shows/{showId}")
    public ApiResponse<ShowInfo> updateShow(@PathVariable Long showId,
                                            @Valid @RequestBody AdminUpdateShowRequest body) {
        return ApiResponse.successZero(adminBusinessService.updateShow(showId, body));
    }

    @PostMapping("/shows/{showId}/publish")
    public ApiResponse<Void> publishShow(@PathVariable Long showId) {
        adminBusinessService.publishShow(showId);
        return ApiResponse.success();
    }

    @PostMapping("/shows/{showId}/offline")
    public ApiResponse<Void> offlineShow(@PathVariable Long showId) {
        adminBusinessService.offlineShow(showId);
        return ApiResponse.success();
    }

    @GetMapping("/shows/{showId}/sessions")
    public ApiResponse<List<PerformanceSession>> listSessions(@PathVariable Long showId) {
        return ApiResponse.successZero(adminBusinessService.listSessions(showId));
    }

    @PostMapping("/shows/{showId}/sessions")
    public ApiResponse<PerformanceSession> createSession(@PathVariable Long showId,
                                                         @Valid @RequestBody AdminCreateSessionRequest body) {
        return ApiResponse.successZero(adminBusinessService.createSession(showId, body));
    }

    @PutMapping("/sessions/{sessionId}")
    public ApiResponse<PerformanceSession> updateSession(@PathVariable Long sessionId,
                                                         @Valid @RequestBody AdminUpdateSessionRequest body) {
        return ApiResponse.successZero(adminBusinessService.updateSession(sessionId, body));
    }

    @PostMapping("/sessions/{sessionId}/publish")
    public ApiResponse<Void> publishSession(@PathVariable Long sessionId) {
        adminBusinessService.publishSession(sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/{sessionId}/offline")
    public ApiResponse<Void> offlineSession(@PathVariable Long sessionId) {
        adminBusinessService.offlineSession(sessionId);
        return ApiResponse.success();
    }

    @GetMapping("/sessions/{sessionId}/ticket-categories")
    public ApiResponse<List<TicketCategory>> listTicketCategories(@PathVariable Long sessionId) {
        return ApiResponse.successZero(adminBusinessService.listTicketCategories(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/ticket-categories")
    public ApiResponse<TicketCategory> createTicketCategory(@PathVariable Long sessionId,
                                                            @Valid @RequestBody AdminCreateTicketCategoryRequest body) {
        return ApiResponse.successZero(adminBusinessService.createTicketCategory(sessionId, body));
    }

    @PutMapping("/ticket-categories/{ticketCategoryId}")
    public ApiResponse<TicketCategory> updateTicketCategory(@PathVariable Long ticketCategoryId,
                                                            @Valid @RequestBody AdminUpdateTicketCategoryRequest body) {
        return ApiResponse.successZero(adminBusinessService.updateTicketCategory(ticketCategoryId, body));
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/publish")
    public ApiResponse<Void> publishTicketCategory(@PathVariable Long ticketCategoryId) {
        adminBusinessService.publishTicketCategory(ticketCategoryId);
        return ApiResponse.success();
    }

    @PostMapping("/ticket-categories/{ticketCategoryId}/offline")
    public ApiResponse<Void> offlineTicketCategory(@PathVariable Long ticketCategoryId) {
        adminBusinessService.offlineTicketCategory(ticketCategoryId);
        return ApiResponse.success();
    }
}
