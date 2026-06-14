package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.AdminCreateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateShowRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateTicketCategoryRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateShowRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateTicketCategoryRequest;
import com.zewbby.smartticket.domain.dto.AdjustStockRequest;
import com.zewbby.smartticket.domain.dto.InitStockRequest;
import com.zewbby.smartticket.domain.entity.PerformanceSession;
import com.zewbby.smartticket.domain.entity.ShowInfo;
import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.domain.vo.AdminStockVO;

import java.util.List;

public interface AdminBusinessService {

    List<ShowInfo> listShows(String status, Integer limit);

    ShowInfo getShow(Long showId);

    ShowInfo createShow(AdminCreateShowRequest request);

    ShowInfo updateShow(Long showId, AdminUpdateShowRequest request);

    void publishShow(Long showId);

    void offlineShow(Long showId);

    List<PerformanceSession> listSessions(Long showId);

    PerformanceSession createSession(Long showId, AdminCreateSessionRequest request);

    PerformanceSession updateSession(Long sessionId, AdminUpdateSessionRequest request);

    void publishSession(Long sessionId);

    void offlineSession(Long sessionId);

    List<TicketCategory> listTicketCategories(Long sessionId);

    TicketCategory createTicketCategory(Long sessionId, AdminCreateTicketCategoryRequest request);

    TicketCategory updateTicketCategory(Long ticketCategoryId, AdminUpdateTicketCategoryRequest request);

    void publishTicketCategory(Long ticketCategoryId);

    void offlineTicketCategory(Long ticketCategoryId);

    AdminStockVO initStock(Long ticketCategoryId, InitStockRequest request);

    AdminStockVO adjustStock(Long ticketCategoryId, AdjustStockRequest request);

    AdminStockVO preheatStock(Long ticketCategoryId);

    List<AdminStockVO> preheatAllStock();

    AdminStockVO getStock(Long ticketCategoryId);

    List<AdminStockVO> listStocks();
}
