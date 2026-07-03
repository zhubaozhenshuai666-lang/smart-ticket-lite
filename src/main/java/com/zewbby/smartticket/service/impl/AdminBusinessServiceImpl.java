package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.aop.AdminAudit;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
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
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.entity.TicketStockBucket;
import com.zewbby.smartticket.domain.entity.Venue;
import com.zewbby.smartticket.domain.vo.AdminStockVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.ShowStatusEnum;
import com.zewbby.smartticket.enums.TicketCategoryStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.ShowMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.VenueMapper;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.ShowRelationCacheService;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockLuaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminBusinessServiceImpl implements AdminBusinessService {

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    private final VenueMapper venueMapper;

    private final ShowMapper showMapper;

    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketStockMapper ticketStockMapper;

    private final TicketStockBucketMapper ticketStockBucketMapper;

    private final OrderMapper orderMapper;

    private final OrderRequestMapper orderRequestMapper;

    private final StockCacheService stockCacheService;

    private final StockLuaService stockLuaService;

    private final StockBucketProperties stockBucketProperties;

    private final ShowRelationCacheService showRelationCacheService;

    @Autowired
    public AdminBusinessServiceImpl(VenueMapper venueMapper,
                                    ShowMapper showMapper,
                                    TicketCategoryMapper ticketCategoryMapper,
                                    TicketStockMapper ticketStockMapper,
                                    TicketStockBucketMapper ticketStockBucketMapper,
                                    OrderMapper orderMapper,
                                    OrderRequestMapper orderRequestMapper,
                                    StockCacheService stockCacheService,
                                    StockLuaService stockLuaService,
                                    StockBucketProperties stockBucketProperties,
                                    ShowRelationCacheService showRelationCacheService) {
        this.venueMapper = venueMapper;
        this.showMapper = showMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.ticketStockBucketMapper = ticketStockBucketMapper;
        this.orderMapper = orderMapper;
        this.orderRequestMapper = orderRequestMapper;
        this.stockCacheService = stockCacheService;
        this.stockLuaService = stockLuaService;
        this.stockBucketProperties = stockBucketProperties;
        this.showRelationCacheService = showRelationCacheService;
    }

    AdminBusinessServiceImpl(VenueMapper venueMapper,
                             ShowMapper showMapper,
                             TicketCategoryMapper ticketCategoryMapper,
                             TicketStockMapper ticketStockMapper,
                             OrderMapper orderMapper,
                             OrderRequestMapper orderRequestMapper,
                             StockCacheService stockCacheService,
                             StockLuaService stockLuaService) {
        this(venueMapper,
                showMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                null,
                orderMapper,
                orderRequestMapper,
                stockCacheService,
                stockLuaService,
                disabledBucketProperties(),
                null);
    }

    private static StockBucketProperties disabledBucketProperties() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    @Override
    public List<ShowInfo> listShows(String status, Integer limit) {
        return showMapper.adminSelectShows(status, normalizeLimit(limit));
    }

    @Override
    public ShowInfo getShow(Long showId) {
        return requireShow(showId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SHOW_CREATE, resourceType = "SHOW", resultId = "#result.id")
    @Transactional
    public ShowInfo createShow(AdminCreateShowRequest request) {
        requireVenue(request.getVenueId());
        LocalDateTime now = LocalDateTime.now();
        ShowInfo showInfo = new ShowInfo();
        showInfo.setTitle(request.getTitle());
        showInfo.setArtist(request.getArtist());
        showInfo.setVenueId(request.getVenueId());
        showInfo.setDescription(request.getDescription());
        showInfo.setStatus(ShowStatusEnum.DRAFT.getCode());
        showInfo.setCreatedAt(now);
        showInfo.setUpdatedAt(now);
        showMapper.insertShow(showInfo);
        return showInfo;
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SHOW_UPDATE, resourceType = "SHOW", resourceId = "#p0")
    @Transactional
    public ShowInfo updateShow(Long showId, AdminUpdateShowRequest request) {
        ShowInfo existing = requireShow(showId);
        ensureShowMetadataEditable(existing);
        requireVenue(request.getVenueId());
        ShowInfo showInfo = new ShowInfo();
        showInfo.setId(showId);
        showInfo.setTitle(request.getTitle());
        showInfo.setArtist(request.getArtist());
        showInfo.setVenueId(request.getVenueId());
        showInfo.setDescription(request.getDescription());
        showMapper.updateShow(showInfo);
        return requireShow(showId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SHOW_PUBLISH, resourceType = "SHOW", resourceId = "#p0")
    @Transactional
    public void publishShow(Long showId) {
        requireShow(showId);
        ensureShowNotStarted(showId);
        showMapper.updateShowStatus(showId, ShowStatusEnum.PUBLISHED.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SHOW_OFFLINE, resourceType = "SHOW", resourceId = "#p0")
    @Transactional
    public void offlineShow(Long showId) {
        ShowInfo existing = requireShow(showId);
        ensureShowMetadataEditable(existing);
        if (orderMapper.countPendingByShowId(showId) > 0) {
            throw new BusinessException("存在待支付订单，不能下架演出");
        }
        showMapper.updateShowStatus(showId, ShowStatusEnum.OFFLINE.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    public List<PerformanceSession> listSessions(Long showId) {
        requireShow(showId);
        return showMapper.adminSelectSessionsByShowId(showId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SESSION_CREATE, resourceType = "SESSION", resultId = "#result.id")
    @Transactional
    public PerformanceSession createSession(Long showId, AdminCreateSessionRequest request) {
        ShowInfo showInfo = requireShow(showId);
        ensureShowMetadataEditable(showInfo);
        validateSessionTime(request.getStartTime(), request.getEndTime());
        LocalDateTime now = LocalDateTime.now();
        PerformanceSession session = new PerformanceSession();
        session.setShowId(showId);
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        session.setStatus(ShowStatusEnum.DRAFT.getCode());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        showMapper.insertSession(session);
        return session;
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SESSION_UPDATE, resourceType = "SESSION", resourceId = "#p0")
    @Transactional
    public PerformanceSession updateSession(Long sessionId, AdminUpdateSessionRequest request) {
        PerformanceSession existing = requireSession(sessionId);
        ensureSessionMetadataEditable(existing);
        validateSessionTime(request.getStartTime(), request.getEndTime());
        PerformanceSession session = new PerformanceSession();
        session.setId(sessionId);
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        showMapper.updateSession(session);
        return requireSession(sessionId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SESSION_PUBLISH, resourceType = "SESSION", resourceId = "#p0")
    @Transactional
    public void publishSession(Long sessionId) {
        PerformanceSession session = requireSession(sessionId);
        requireShow(session.getShowId());
        ensureSessionNotStarted(session, "场次已开演，禁止发布");
        showMapper.updateSessionStatus(sessionId, ShowStatusEnum.PUBLISHED.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.SESSION_OFFLINE, resourceType = "SESSION", resourceId = "#p0")
    @Transactional
    public void offlineSession(Long sessionId) {
        PerformanceSession existing = requireSession(sessionId);
        ensureSessionMetadataEditable(existing);
        if (orderMapper.countPendingBySessionId(sessionId) > 0) {
            throw new BusinessException("存在待支付订单，不能下架场次");
        }
        showMapper.updateSessionStatus(sessionId, ShowStatusEnum.OFFLINE.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    public List<TicketCategory> listTicketCategories(Long sessionId) {
        requireSession(sessionId);
        return ticketCategoryMapper.adminSelectBySessionId(sessionId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.TICKET_CATEGORY_CREATE, resourceType = "TICKET_CATEGORY", resultId = "#result.id")
    @Transactional
    public TicketCategory createTicketCategory(Long sessionId, AdminCreateTicketCategoryRequest request) {
        PerformanceSession session = requireSession(sessionId);
        ensureSessionMetadataEditable(session);
        validatePrice(request.getPrice());
        LocalDateTime now = LocalDateTime.now();
        TicketCategory ticketCategory = new TicketCategory();
        ticketCategory.setSessionId(sessionId);
        ticketCategory.setCategoryName(request.getCategoryName());
        ticketCategory.setPrice(request.getPrice());
        ticketCategory.setStatus(TicketCategoryStatusEnum.DRAFT.getCode());
        ticketCategory.setCreatedAt(now);
        ticketCategory.setUpdatedAt(now);
        ticketCategoryMapper.insert(ticketCategory);
        return ticketCategory;
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.TICKET_CATEGORY_UPDATE, resourceType = "TICKET_CATEGORY", resourceId = "#p0")
    @Transactional
    public TicketCategory updateTicketCategory(Long ticketCategoryId, AdminUpdateTicketCategoryRequest request) {
        TicketCategory existing = requireTicketCategory(ticketCategoryId);
        ensureTicketCategoryMetadataEditable(existing);
        validatePrice(request.getPrice());
        /*
         * 票档已有订单后不应该随便改价格。
         * 订单金额来自下单时的 ticket_category.price，后续支付、退款、对账都依赖历史金额语义。
         * 这里直接拦截价格变更，避免出现“同一个票档历史订单和新订单价格混杂但没有版本”的问题。
         */
        if (existing.getPrice() != null
                && existing.getPrice().compareTo(request.getPrice()) != 0
                && orderMapper.countByTicketCategoryId(ticketCategoryId) > 0) {
            throw new BusinessException("票档已有订单，不能修改价格");
        }
        TicketCategory ticketCategory = new TicketCategory();
        ticketCategory.setId(ticketCategoryId);
        ticketCategory.setCategoryName(request.getCategoryName());
        ticketCategory.setPrice(request.getPrice());
        ticketCategoryMapper.update(ticketCategory);
        return requireTicketCategory(ticketCategoryId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.TICKET_CATEGORY_PUBLISH, resourceType = "TICKET_CATEGORY", resourceId = "#p0")
    @Transactional
    public void publishTicketCategory(Long ticketCategoryId) {
        TicketCategory ticketCategory = requireTicketCategory(ticketCategoryId);
        ensureTicketCategorySessionNotStarted(ticketCategory, "票档所属场次已开演，禁止发布票档");
        ticketCategoryMapper.updateStatus(ticketCategoryId, TicketCategoryStatusEnum.PUBLISHED.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.TICKET_CATEGORY_OFFLINE, resourceType = "TICKET_CATEGORY", resourceId = "#p0")
    @Transactional
    public void offlineTicketCategory(Long ticketCategoryId) {
        TicketCategory existing = requireTicketCategory(ticketCategoryId);
        ensureTicketCategoryMetadataEditable(existing);
        if (orderMapper.countPendingByTicketCategoryId(ticketCategoryId) > 0) {
            throw new BusinessException("存在待支付订单，不能下架票档");
        }
        ticketCategoryMapper.updateStatus(ticketCategoryId, TicketCategoryStatusEnum.OFFLINE.getCode());
        refreshShowRelationCacheIfAvailable();
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_INIT, resourceType = "TICKET_STOCK", resourceId = "#p0")
    @Transactional
    public AdminStockVO initStock(Long ticketCategoryId, InitStockRequest request) {
        requireTicketCategory(ticketCategoryId);
        Integer availableStock = request.getAvailableStock();
        if (availableStock == null || availableStock < 0) {
            throw new BusinessException("初始化库存不能小于0");
        }
        int inFlight = getInFlightDeductedQuantity(ticketCategoryId);
        if (inFlight > 0) {
            throw new BusinessException("存在在途预扣请求，禁止初始化库存");
        }
        TicketStock existing = ticketStockMapper.selectByTicketCategoryId(ticketCategoryId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            TicketStock stock = new TicketStock();
            stock.setTicketCategoryId(ticketCategoryId);
            stock.setTotalStock(availableStock);
            stock.setAvailableStock(availableStock);
            stock.setLockedStock(0);
            stock.setSoldStock(0);
            stock.setVersion(0);
            stock.setCreatedAt(now);
            stock.setUpdatedAt(now);
            ticketStockMapper.insert(stock);
        } else {
            /*
             * 库存初始化是高风险操作，只允许在没有锁定库存和售出库存时执行。
             * 已经有 locked/sold 后重新初始化，相当于抹掉交易事实，会让订单、支付和库存对不上。
             */
            if (positive(existing.getLockedStock()) || positive(existing.getSoldStock())) {
                throw new BusinessException("已有锁定或售出库存，禁止重新初始化");
            }
            if (ticketStockMapper.initExistingStock(ticketCategoryId, availableStock) == 0) {
                throw new BusinessException("库存初始化失败");
            }
        }
        if (stockBucketProperties.isEnabled()) {
            rebuildBuckets(ticketCategoryId, availableStock);
        }
        return preheatStock(ticketCategoryId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_ADJUST, resourceType = "TICKET_STOCK", resourceId = "#p0")
    @Transactional
    public AdminStockVO adjustStock(Long ticketCategoryId, AdjustStockRequest request) {
        requireTicketCategory(ticketCategoryId);
        if (request.getAdjustQuantity() == null || request.getAdjustQuantity() == 0) {
            throw new BusinessException("库存调整数量不能为0");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessException("库存调整原因不能为空");
        }
        TicketStock stock = requireStock(ticketCategoryId);
        int inFlight = getInFlightDeductedQuantity(ticketCategoryId);
        int expectedAfterAdjust = stock.getAvailableStock() + request.getAdjustQuantity() - inFlight;
        if (expectedAfterAdjust < 0) {
            throw new BusinessException("在途预扣量过大，调整后Redis可售库存会为负");
        }
        /*
         * 库存调整使用增量，而不是 targetAvailableStock 直接覆盖。
         * 增量能保留 locked_stock/sold_stock 的交易事实，也更容易审计“这次到底追加或扣减了多少票”。
         */
        if (ticketStockMapper.adjustAvailableStock(ticketCategoryId, request.getAdjustQuantity()) == 0) {
            throw new BusinessException("库存调整后可售库存不能小于0");
        }
        if (stockBucketProperties.isEnabled()) {
            adjustBuckets(ticketCategoryId, request.getAdjustQuantity());
        }
        return preheatStock(ticketCategoryId);
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_PREHEAT, resourceType = "TICKET_STOCK", resourceId = "#p0")
    public AdminStockVO preheatStock(Long ticketCategoryId) {
        TicketStock stock = requireStock(ticketCategoryId);
        int inFlight = getInFlightDeductedQuantity(ticketCategoryId);
        int expectedRedisAvailable = stock.getAvailableStock() - inFlight;
        if (expectedRedisAvailable < 0) {
            throw new BusinessException("在途预扣量超过MySQL可售库存，拒绝预热");
        }
        Integer beforeRedisStock = stockCacheService.getAvailableStock(ticketCategoryId);
        if (stockBucketProperties.isEnabled()) {
            preheatBucketStock(ticketCategoryId, stockBucketProperties.getDefaultBucketCount());
            return buildStockVO(requireStock(ticketCategoryId));
        }
        /*
         * 后台 Redis 预热必须考虑在途预扣量。
         *
         * MySQL available_stock 表示最终持久化可售库存；PRE_DEDUCTED/QUEUED/PROCESSING 请求已经在 Redis 里先扣了，
         * 但消费者还没把 MySQL available_stock 扣到 locked_stock。因此 expectedRedisAvailable =
         * MySQL available_stock - inFlightDeductedQuantity。
         *
         * 如果 Redis key 已存在，不能无保护 SET 覆盖。这里用 Lua CAS + Delta：只有 Redis 当前值仍等于刚读到的值，
         * 才允许补差值；如果期间发生并发下单，本次预热放弃，让后台重新检查，避免把库存修坏。
         */
        if (beforeRedisStock == null) {
            stockCacheService.setAvailableStock(ticketCategoryId, expectedRedisAvailable);
        } else {
            int delta = expectedRedisAvailable - beforeRedisStock;
            if (delta != 0) {
                RedisStockRepairResult result = stockLuaService.repairStockByCasDelta(
                        ticketCategoryId,
                        beforeRedisStock,
                        delta
                );
                if (result != RedisStockRepairResult.SUCCESS) {
                    throw new BusinessException("Redis库存预热失败: " + result.getMessage());
                }
            }
        }
        if (expectedRedisAvailable > 0) {
            stockCacheService.clearSoldout(ticketCategoryId);
        }
        return buildStockVO(requireStock(ticketCategoryId));
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_PREHEAT, resourceType = "TICKET_STOCK", resourceId = "ALL")
    public List<AdminStockVO> preheatAllStock() {
        List<TicketStock> stocks = ticketStockMapper.selectAll();
        List<AdminStockVO> result = new ArrayList<>();
        for (TicketStock stock : stocks) {
            result.add(preheatStock(stock.getTicketCategoryId()));
        }
        return result;
    }

    @Override
    public AdminStockVO getStock(Long ticketCategoryId) {
        return buildStockVO(requireStock(ticketCategoryId));
    }

    @Override
    public List<AdminStockVO> listStocks() {
        List<TicketStock> stocks = ticketStockMapper.selectAll();
        List<AdminStockVO> result = new ArrayList<>();
        for (TicketStock stock : stocks) {
            result.add(buildStockVO(stock));
        }
        return result;
    }

    private AdminStockVO buildStockVO(TicketStock stock) {
        int inFlight = getInFlightDeductedQuantity(stock.getTicketCategoryId());
        int expectedRedisAvailable = stock.getAvailableStock() - inFlight;
        Integer redisAvailableStock = stockCacheService.getAvailableStock(stock.getTicketCategoryId());
        if (stockBucketProperties.isEnabled()) {
            redisAvailableStock = stockCacheService.sumBucketAvailableStock(
                    stock.getTicketCategoryId(),
                    stockBucketProperties.getActiveVersion(),
                    stockBucketProperties.getDefaultBucketCount()
            );
        }
        Integer diff = redisAvailableStock == null ? null : redisAvailableStock - expectedRedisAvailable;
        boolean mysqlConsistent = stock.getTotalStock() != null
                && stock.getAvailableStock() != null
                && stock.getLockedStock() != null
                && stock.getSoldStock() != null
                && stock.getTotalStock().equals(stock.getAvailableStock() + stock.getLockedStock() + stock.getSoldStock());
        boolean redisConsistent = redisAvailableStock != null && redisAvailableStock.equals(expectedRedisAvailable);
        return new AdminStockVO(
                stock.getTicketCategoryId(),
                stock.getTotalStock(),
                stock.getAvailableStock(),
                stock.getLockedStock(),
                stock.getSoldStock(),
                redisAvailableStock,
                isRedisSoldOut(stock.getTicketCategoryId()),
                inFlight,
                expectedRedisAvailable,
                diff,
                mysqlConsistent,
                redisConsistent
        );
    }

    private boolean isRedisSoldOut(Long ticketCategoryId) {
        if (!stockBucketProperties.isEnabled()) {
            return stockCacheService.isSoldOut(ticketCategoryId);
        }
        return stockCacheService.isSoldOut(ticketCategoryId, stockBucketProperties.getActiveVersion());
    }

    private ShowInfo requireShow(Long showId) {
        ShowInfo showInfo = showMapper.selectShowInfoById(showId);
        if (showInfo == null) {
            throw new BusinessException(ErrorMessageConstant.SHOW_NOT_FOUND);
        }
        return showInfo;
    }

    private PerformanceSession requireSession(Long sessionId) {
        PerformanceSession session = showMapper.selectSessionById(sessionId);
        if (session == null) {
            throw new BusinessException("场次不存在");
        }
        return session;
    }

    private TicketCategory requireTicketCategory(Long ticketCategoryId) {
        TicketCategory ticketCategory = ticketCategoryMapper.selectById(ticketCategoryId);
        if (ticketCategory == null) {
            throw new BusinessException(ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        }
        return ticketCategory;
    }

    private TicketStock requireStock(Long ticketCategoryId) {
        TicketStock stock = ticketStockMapper.selectByTicketCategoryId(ticketCategoryId);
        if (stock == null) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_FOUND);
        }
        return stock;
    }

    private void ensureShowMetadataEditable(ShowInfo showInfo) {
        if (isPublished(showInfo.getStatus())) {
            throw new BusinessException("开售期间演出元数据已冻结，禁止修改或下架");
        }
        ensureShowNotStarted(showInfo.getId());
    }

    private void ensureSessionMetadataEditable(PerformanceSession session) {
        if (isPublished(session.getStatus())) {
            throw new BusinessException("开售期间场次元数据已冻结，禁止修改或下架");
        }
        ensureSessionNotStarted(session, "场次已开演，基础元数据禁止修改或下架");
    }

    private void ensureTicketCategoryMetadataEditable(TicketCategory ticketCategory) {
        if (isPublished(ticketCategory.getStatus())) {
            throw new BusinessException("开售期间票档元数据已冻结，禁止修改或下架");
        }
        ensureTicketCategorySessionNotStarted(ticketCategory, "票档所属场次已开演，基础元数据禁止修改或下架");
    }

    private boolean isPublished(String status) {
        return ShowStatusEnum.PUBLISHED.getCode().equals(status)
                || TicketCategoryStatusEnum.PUBLISHED.getCode().equals(status);
    }

    private void ensureShowNotStarted(Long showId) {
        List<PerformanceSession> sessions = showMapper.adminSelectSessionsByShowId(showId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (PerformanceSession session : sessions) {
            if (hasStarted(session, now)) {
                throw new BusinessException("演出已有场次开演，基础元数据禁止修改、下架或发布");
            }
        }
    }

    private void ensureTicketCategorySessionNotStarted(TicketCategory ticketCategory, String message) {
        PerformanceSession session = requireSession(ticketCategory.getSessionId());
        ensureSessionNotStarted(session, message);
    }

    private void ensureSessionNotStarted(PerformanceSession session, String message) {
        if (hasStarted(session, LocalDateTime.now())) {
            throw new BusinessException(message);
        }
    }

    private boolean hasStarted(PerformanceSession session, LocalDateTime now) {
        return session.getStartTime() != null && !session.getStartTime().isAfter(now);
    }

    private void refreshShowRelationCacheIfAvailable() {
        if (showRelationCacheService != null) {
            showRelationCacheService.refreshPublishedRelations();
        }
    }

    private Venue requireVenue(Long venueId) {
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null) {
            throw new BusinessException("场馆不存在");
        }
        return venue;
    }

    private int getInFlightDeductedQuantity(Long ticketCategoryId) {
        Integer value = orderRequestMapper.sumInFlightDeductedQuantity(ticketCategoryId);
        return value == null ? 0 : value;
    }

    private void rebuildBuckets(Long ticketCategoryId, Integer totalAvailableStock) {
        int activeVersion = stockBucketProperties.getActiveVersion();
        if (ticketStockBucketMapper.countLockedOrSoldByTicketCategoryIdAndVersion(ticketCategoryId, activeVersion) > 0) {
            throw new BusinessException("当前版本已有bucket锁定或售出库存，禁止重建库存bucket");
        }
        int bucketCount = stockBucketProperties.getDefaultBucketCount();
        if (bucketCount <= 0) {
            throw new BusinessException("库存bucket数量必须大于0");
        }
        ticketStockBucketMapper.deleteByTicketCategoryIdAndVersion(ticketCategoryId, activeVersion);
        int base = totalAvailableStock / bucketCount;
        int remainder = totalAvailableStock % bucketCount;
        LocalDateTime now = LocalDateTime.now();
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            int bucketStock = base + (bucketNo < remainder ? 1 : 0);
            TicketStockBucket bucket = new TicketStockBucket();
            bucket.setTicketCategoryId(ticketCategoryId);
            bucket.setBucketVersion(activeVersion);
            bucket.setBucketNo(bucketNo);
            bucket.setTotalStock(bucketStock);
            bucket.setAvailableStock(bucketStock);
            bucket.setLockedStock(0);
            bucket.setSoldStock(0);
            bucket.setVersion(0);
            bucket.setCreatedAt(now);
            bucket.setUpdatedAt(now);
            ticketStockBucketMapper.insert(bucket);
        }
    }

    private void adjustBuckets(Long ticketCategoryId, Integer adjustQuantity) {
        int bucketCount = stockBucketProperties.getDefaultBucketCount();
        int activeVersion = stockBucketProperties.getActiveVersion();
        if (adjustQuantity > 0) {
            int base = adjustQuantity / bucketCount;
            int remainder = adjustQuantity % bucketCount;
            for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
                int delta = base + (bucketNo < remainder ? 1 : 0);
                if (delta > 0 && ticketStockBucketMapper.adjustAvailableStockByVersion(ticketCategoryId, activeVersion, bucketNo, delta) != 1) {
                    throw new BusinessException("库存bucket追加失败");
                }
            }
            return;
        }

        int remainingToReduce = Math.abs(adjustQuantity);
        List<TicketStockBucket> buckets = ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(ticketCategoryId, activeVersion);
        for (TicketStockBucket bucket : buckets) {
            if (remainingToReduce <= 0) {
                break;
            }
            int reducible = Math.min(positiveValue(bucket.getAvailableStock()), remainingToReduce);
            if (reducible <= 0) {
                continue;
            }
            if (ticketStockBucketMapper.adjustAvailableStockByVersion(ticketCategoryId, activeVersion, bucket.getBucketNo(), -reducible) != 1) {
                throw new BusinessException("库存bucket扣减调整失败");
            }
            remainingToReduce -= reducible;
        }
        if (remainingToReduce > 0) {
            throw new BusinessException("库存bucket可售库存不足，无法完成扣减调整");
        }
    }

    private void preheatBucketStock(Long ticketCategoryId, int bucketCount) {
        int activeVersion = stockBucketProperties.getActiveVersion();
        List<TicketStockBucket> buckets = ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(
                ticketCategoryId,
                activeVersion
        );
        if (buckets.size() != bucketCount) {
            throw new BusinessException("库存bucket未初始化，请先初始化库存");
        }
        stockCacheService.setBucketCount(ticketCategoryId, activeVersion, bucketCount);
        for (TicketStockBucket bucket : buckets) {
            stockCacheService.setBucketAvailableStock(
                    ticketCategoryId,
                    activeVersion,
                    bucket.getBucketNo(),
                    positiveValue(bucket.getAvailableStock())
            );
        }
        stockCacheService.clearAllBucketSoldout(ticketCategoryId, activeVersion, bucketCount);
    }

    private int positiveValue(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private void validateSessionTime(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessException("场次时间非法");
        }
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("票档价格必须大于0");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
