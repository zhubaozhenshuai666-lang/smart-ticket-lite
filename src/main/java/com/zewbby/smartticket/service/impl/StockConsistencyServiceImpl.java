package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.aop.AdminAudit;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.StockCompensationRecord;
import com.zewbby.smartticket.domain.entity.StockConsistencyRecord;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.vo.StockConsistencyVO;
import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.StockCheckTypeEnum;
import com.zewbby.smartticket.enums.StockCompensationStatusEnum;
import com.zewbby.smartticket.enums.StockCompensationTypeEnum;
import com.zewbby.smartticket.enums.StockConsistencyRecordStatusEnum;
import com.zewbby.smartticket.enums.StockRepairStrategyEnum;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.StockCompensationRecordMapper;
import com.zewbby.smartticket.mapper.StockConsistencyRecordMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockConsistencyService;
import com.zewbby.smartticket.service.StockLuaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockConsistencyServiceImpl implements StockConsistencyService {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private static final int MAX_BATCH_SIZE = 500;

    private static final int DEFAULT_RECORD_LIMIT = 50;

    private static final int MAX_RECORD_LIMIT = 200;

    private final TicketStockMapper ticketStockMapper;

    private final OrderRequestMapper orderRequestMapper;

    private final StockConsistencyRecordMapper consistencyRecordMapper;

    private final StockCompensationRecordMapper compensationRecordMapper;

    private final StockCacheService stockCacheService;

    private final StockLuaService stockLuaService;

    private final StockBucketProperties stockBucketProperties;

    @Autowired
    public StockConsistencyServiceImpl(TicketStockMapper ticketStockMapper,
                                       OrderRequestMapper orderRequestMapper,
                                       StockConsistencyRecordMapper consistencyRecordMapper,
                                       StockCompensationRecordMapper compensationRecordMapper,
                                       StockCacheService stockCacheService,
                                       StockLuaService stockLuaService,
                                       StockBucketProperties stockBucketProperties) {
        this.ticketStockMapper = ticketStockMapper;
        this.orderRequestMapper = orderRequestMapper;
        this.consistencyRecordMapper = consistencyRecordMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stockCacheService = stockCacheService;
        this.stockLuaService = stockLuaService;
        this.stockBucketProperties = stockBucketProperties;
    }

    public StockConsistencyServiceImpl(TicketStockMapper ticketStockMapper,
                                       OrderRequestMapper orderRequestMapper,
                                       StockConsistencyRecordMapper consistencyRecordMapper,
                                       StockCompensationRecordMapper compensationRecordMapper,
                                       StockCacheService stockCacheService,
                                       StockLuaService stockLuaService) {
        this(ticketStockMapper,
                orderRequestMapper,
                consistencyRecordMapper,
                compensationRecordMapper,
                stockCacheService,
                stockLuaService,
                disabledBucketProperties());
    }

    private static StockBucketProperties disabledBucketProperties() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    @Override
    public StockConsistencyVO checkStockConsistency(Long ticketCategoryId) {
        return checkOne(ticketCategoryId, StockCheckTypeEnum.MANUAL.getCode());
    }

    /**
     * 检查单个票档库存一致性。
     *
     * 这里不能再简单比较 Redis available_stock 和 MySQL available_stock。
     * 异步下单入口会先 Redis 预扣，MQ 消费者稍后才扣 MySQL；这段时间 Redis 会比 MySQL 少一部分库存，
     * 这不是错误，而是在途请求占用。真正应该比较的是：
     * expectedRedisAvailable = mysql.available_stock - inFlightDeductedQuantity。
     */
    @Override
    public StockConsistencyVO checkOne(Long ticketCategoryId, String checkType) {
        TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(ticketCategoryId);
        if (ticketStock == null) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_FOUND);
        }
        return checkTicketStock(ticketStock, StockCheckTypeEnum.normalize(checkType));
    }

    /**
     * 分页检查所有票档。
     *
     * 库存巡检不能一次 select all：真实项目票档可能很多，一次性拉全量会拖慢数据库和应用内存。
     * 这里按 ticket_stock.id 递增分页，每批最多处理 batchSize 条，适合定时任务和人工全量检查复用。
     */
    @Override
    public List<StockConsistencyVO> checkAll(String checkType, Integer batchSize) {
        int limit = normalizeBatchSize(batchSize);
        List<StockConsistencyVO> result = new ArrayList<>();
        long lastId = 0L;
        while (true) {
            List<TicketStock> page = ticketStockMapper.selectPageAfterId(lastId, limit);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (TicketStock ticketStock : page) {
                result.add(checkTicketStock(ticketStock, StockCheckTypeEnum.normalize(checkType)));
                lastId = ticketStock.getId();
            }
            if (page.size() < limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public List<StockConsistencyRecord> listRecords(String status, Integer limit) {
        return consistencyRecordMapper.selectRecent(status, normalizeRecordLimit(limit));
    }

    /**
     * 使用 Lua CAS + Delta 修复 Redis 库存。
     *
     * 修复前必须重新计算 expectedRedisAvailable，不能直接信旧记录，因为旧记录创建后可能已经有新的下单、
     * 取消或支付动作改变库存语义。正常售卖期间也不能无保护 SET 覆盖 Redis 库存；SET 会抹掉并发预扣。
     * Lua CAS 会确认 Redis 当前值仍等于 beforeRedisStock 才 INCRBY delta，否则返回并发修改，要求重新 check。
     */
    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_REPAIR, resourceType = "STOCK_CONSISTENCY_RECORD", resourceId = "#p0")
    @Transactional
    public void repairRecord(Long recordId) {
        StockConsistencyRecord record = consistencyRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("库存一致性记录不存在");
        }
        if (!StockConsistencyRecordStatusEnum.PENDING.getCode().equals(record.getStatus())
                && !StockConsistencyRecordStatusEnum.FAILED.getCode().equals(record.getStatus())) {
            throw new BusinessException("当前一致性记录状态不允许修复");
        }
        if (stockBucketProperties.isEnabled()) {
            throw new BusinessException("bucket模式下禁止使用总表级Redis修复，请使用后续bucket级巡检修复");
        }

        StockSnapshot snapshot = buildSnapshot(record.getTicketCategoryId());
        if (!snapshot.mysqlStockConsistent()) {
            writeRepairFailure(record, snapshot, null, "MySQL库存不守恒，拒绝自动修复Redis");
            return;
        }
        if (snapshot.redisAvailableStock() == null) {
            writeRepairFailure(record, snapshot, null, "Redis库存key不存在，拒绝无保护SET覆盖");
            return;
        }

        int beforeRedisStock = snapshot.redisAvailableStock();
        int delta = snapshot.expectedRedisAvailableStock() - beforeRedisStock;
        RedisStockRepairResult repairResult = stockLuaService.repairStockByCasDelta(
                record.getTicketCategoryId(),
                beforeRedisStock,
                delta
        );
        Integer afterRedisStock = repairResult == RedisStockRepairResult.SUCCESS
                ? beforeRedisStock + delta
                : stockCacheService.getAvailableStock(record.getTicketCategoryId());

        if (repairResult == RedisStockRepairResult.SUCCESS) {
            stockCacheService.clearSoldoutIfStockPositive(record.getTicketCategoryId(), afterRedisStock);
            insertCompensationRecord(
                    record.getTicketCategoryId(),
                    null,
                    record.getId(),
                    StockCompensationTypeEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                    beforeRedisStock,
                    afterRedisStock,
                    snapshot,
                    delta,
                    StockCompensationStatusEnum.SUCCESS.getCode(),
                    "Redis库存已按expectedRedisAvailable修复"
            );
            consistencyRecordMapper.markRepaired(
                    record.getId(),
                    StockRepairStrategyEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                    "SUCCESS, delta=" + delta,
                    LocalDateTime.now()
            );
            return;
        }

        String resultMessage = repairResult.getMessage();
        String status = repairResult == RedisStockRepairResult.CONCURRENT_MODIFIED
                ? StockCompensationStatusEnum.CONCURRENT_MODIFIED.getCode()
                : StockCompensationStatusEnum.FAILED.getCode();
        insertCompensationRecord(
                record.getTicketCategoryId(),
                null,
                record.getId(),
                StockCompensationTypeEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                beforeRedisStock,
                afterRedisStock,
                snapshot,
                delta,
                status,
                resultMessage
        );
        consistencyRecordMapper.markFailed(
                record.getId(),
                StockRepairStrategyEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                resultMessage
        );
    }

    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.STOCK_CONSISTENCY_IGNORE, resourceType = "STOCK_CONSISTENCY_RECORD", resourceId = "#p0")
    public void ignoreRecord(Long recordId) {
        int rows = consistencyRecordMapper.markIgnored(recordId, "人工忽略");
        if (rows != 1) {
            throw new BusinessException("库存一致性记录不存在或状态不允许忽略");
        }
    }

    /**
     * 兜底补偿失败请求的 Redis 预扣。
     *
     * 异步请求可能已经 Redis 预扣成功，但消费者或消息链路最终失败，导致用户没有订单而 Redis 少票。
     * 这里先用 DB 条件更新把 FAILED + NONE/COMPENSATE_FAILED 抢成 COMPENSATING，抢占成功才调用 release Lua；
     * release Lua 再用 requestId 的 deducted/compensated key 防止重复 INCR Redis，双层保护避免库存多加。
     */
    @Override
    @AdminAudit(operation = AdminOperationTypeEnum.ORDER_REQUEST_COMPENSATE, resourceType = "TICKET_ORDER_REQUEST", resourceId = "BATCH")
    @Transactional
    public int compensateFailedRequests(Integer batchSize) {
        List<TicketOrderRequest> requests = orderRequestMapper.selectFailedRequestsNeedCompensation(
                normalizeBatchSize(batchSize)
        );
        int compensatedCount = 0;
        for (TicketOrderRequest request : requests) {
            int claimRows = orderRequestMapper.tryMarkCompensating(request.getId());
            if (claimRows != 1) {
                continue;
            }
            Integer beforeRedisStock = getRedisAvailableStock(request.getTicketCategoryId());
            StockSnapshot snapshot = buildSnapshot(request.getTicketCategoryId());
            try {
                RedisStockReleaseResult releaseResult = stockLuaService.releasePreDeductedStock(
                        request.getRequestId(),
                        request.getTicketCategoryId(),
                        request.getStockBucketVersion(),
                        request.getStockBucketNo(),
                        request.getDeductedQuantity()
                );
                Integer afterRedisStock = getRedisAvailableStock(request.getTicketCategoryId());
                if (releaseResult.isSuccess() || releaseResult == RedisStockReleaseResult.ALREADY_COMPENSATED) {
                    stockCacheService.clearSoldoutIfStockPositive(request.getTicketCategoryId(), afterRedisStock);
                    orderRequestMapper.markCompensated(request.getId(), LocalDateTime.now());
                    insertCompensationRecord(
                            request.getTicketCategoryId(),
                            request.getRequestId(),
                            null,
                            StockCompensationTypeEnum.RELEASE_FAILED_REQUEST_DEDUCTION.getCode(),
                            beforeRedisStock,
                            afterRedisStock,
                            snapshot,
                            request.getDeductedQuantity(),
                            StockCompensationStatusEnum.SUCCESS.getCode(),
                            releaseResult.getMessage()
                    );
                    compensatedCount++;
                } else {
                    markFailedRequestCompensationFailed(request, beforeRedisStock, afterRedisStock, snapshot, releaseResult.getMessage());
                }
            } catch (RuntimeException exception) {
                Integer afterRedisStock = getRedisAvailableStock(request.getTicketCategoryId());
                markFailedRequestCompensationFailed(request, beforeRedisStock, afterRedisStock, snapshot, exception.getMessage());
            }
        }
        return compensatedCount;
    }

    private StockConsistencyVO checkTicketStock(TicketStock ticketStock, String checkType) {
        StockSnapshot snapshot = buildSnapshot(ticketStock);
        Long recordId = null;
        if (needRecord(snapshot)) {
            StockConsistencyRecord record = buildConsistencyRecord(snapshot, checkType);
            consistencyRecordMapper.insert(record);
            recordId = record.getId();
        }
        return toVO(snapshot, recordId);
    }

    private StockSnapshot buildSnapshot(Long ticketCategoryId) {
        TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(ticketCategoryId);
        if (ticketStock == null) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_FOUND);
        }
        return buildSnapshot(ticketStock);
    }

    private StockSnapshot buildSnapshot(TicketStock ticketStock) {
        Integer mysqlStockSum = ticketStock.getAvailableStock()
                + ticketStock.getLockedStock()
                + ticketStock.getSoldStock();
        boolean mysqlStockConsistent = mysqlStockSum.equals(ticketStock.getTotalStock());
        Integer inFlightDeductedQuantity = orderRequestMapper.sumInFlightDeductedQuantity(ticketStock.getTicketCategoryId());
        if (inFlightDeductedQuantity == null) {
            inFlightDeductedQuantity = 0;
        }

        /*
         * expectedRedisAvailable 的计算逻辑：
         * 1. MySQL available_stock 表示已经持久化后的可售库存。
         * 2. PRE_DEDUCTED / QUEUED / PROCESSING 且 redis_deducted=true 的请求，表示 Redis 已经扣了，
         *    但 MySQL 还没有把 available_stock -> locked_stock。
         * 3. 因此 Redis 当前合理值应该是 mysql.available_stock - inFlightDeductedQuantity。
         */
        int expectedRedisAvailableStock = ticketStock.getAvailableStock() - inFlightDeductedQuantity;
        Integer redisAvailableStock = getRedisAvailableStock(ticketStock.getTicketCategoryId());
        int diff = redisAvailableStock == null
                ? expectedRedisAvailableStock
                : redisAvailableStock - expectedRedisAvailableStock;
        return new StockSnapshot(
                ticketStock,
                mysqlStockSum,
                mysqlStockConsistent,
                redisAvailableStock,
                inFlightDeductedQuantity,
                expectedRedisAvailableStock,
                diff
        );
    }

    private Integer getRedisAvailableStock(Long ticketCategoryId) {
        if (!stockBucketProperties.isEnabled()) {
            return stockCacheService.getAvailableStock(ticketCategoryId);
        }
        return stockCacheService.sumBucketAvailableStock(
                ticketCategoryId,
                stockBucketProperties.getActiveVersion(),
                stockBucketProperties.getDefaultBucketCount()
        );
    }

    private boolean needRecord(StockSnapshot snapshot) {
        return !snapshot.mysqlStockConsistent()
                || snapshot.redisAvailableStock() == null
                || !snapshot.redisAvailableStock().equals(snapshot.expectedRedisAvailableStock());
    }

    private StockConsistencyRecord buildConsistencyRecord(StockSnapshot snapshot, String checkType) {
        LocalDateTime now = LocalDateTime.now();
        TicketStock stock = snapshot.ticketStock();
        StockConsistencyRecord record = new StockConsistencyRecord();
        record.setTicketCategoryId(stock.getTicketCategoryId());
        record.setRedisAvailableStock(snapshot.redisAvailableStock());
        record.setMysqlAvailableStock(stock.getAvailableStock());
        record.setMysqlLockedStock(stock.getLockedStock());
        record.setMysqlSoldStock(stock.getSoldStock());
        record.setInFlightDeductedQuantity(snapshot.inFlightDeductedQuantity());
        record.setExpectedRedisAvailableStock(snapshot.expectedRedisAvailableStock());
        record.setDiff(snapshot.diff());
        record.setStatus(StockConsistencyRecordStatusEnum.PENDING.getCode());
        record.setCheckType(checkType);
        record.setRepairStrategy(null);
        record.setRepairResult(null);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setRepairedAt(null);
        return record;
    }

    private StockConsistencyVO toVO(StockSnapshot snapshot, Long recordId) {
        TicketStock stock = snapshot.ticketStock();
        String warningMessage = buildWarningMessage(snapshot);
        return new StockConsistencyVO(
                stock.getTicketCategoryId(),
                stock.getTotalStock(),
                stock.getAvailableStock(),
                stock.getLockedStock(),
                stock.getSoldStock(),
                snapshot.mysqlStockSum(),
                snapshot.redisAvailableStock(),
                snapshot.inFlightDeductedQuantity(),
                snapshot.expectedRedisAvailableStock(),
                snapshot.diff(),
                recordId,
                snapshot.mysqlStockConsistent(),
                snapshot.redisAvailableStock() != null
                        && snapshot.redisAvailableStock().equals(snapshot.expectedRedisAvailableStock()),
                warningMessage
        );
    }

    private String buildWarningMessage(StockSnapshot snapshot) {
        StringBuilder warning = new StringBuilder();
        TicketStock stock = snapshot.ticketStock();
        if (!snapshot.mysqlStockConsistent()) {
            warning.append("MySQL库存不守恒：available + locked + sold = ")
                    .append(snapshot.mysqlStockSum())
                    .append("，total = ")
                    .append(stock.getTotalStock())
                    .append("。");
        }
        if (snapshot.redisAvailableStock() == null) {
            warning.append("Redis库存未预热。");
        } else if (!snapshot.redisAvailableStock().equals(snapshot.expectedRedisAvailableStock())) {
            warning.append("Redis可售库存与expectedRedisAvailable不一致：redisAvailableStock=")
                    .append(snapshot.redisAvailableStock())
                    .append("，mysqlAvailableStock=")
                    .append(stock.getAvailableStock())
                    .append("，inFlightDeductedQuantity=")
                    .append(snapshot.inFlightDeductedQuantity())
                    .append("，expectedRedisAvailableStock=")
                    .append(snapshot.expectedRedisAvailableStock())
                    .append("。");
        }
        if (warning.length() == 0) {
            return "OK";
        }
        return warning.toString();
    }

    private void writeRepairFailure(StockConsistencyRecord record,
                                    StockSnapshot snapshot,
                                    Integer beforeRedisStock,
                                    String resultMessage) {
        insertCompensationRecord(
                record.getTicketCategoryId(),
                null,
                record.getId(),
                StockCompensationTypeEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                beforeRedisStock,
                beforeRedisStock,
                snapshot,
                null,
                StockCompensationStatusEnum.FAILED.getCode(),
                resultMessage
        );
        consistencyRecordMapper.markFailed(
                record.getId(),
                StockRepairStrategyEnum.REPAIR_REDIS_TO_EXPECTED.getCode(),
                resultMessage
        );
    }

    private void markFailedRequestCompensationFailed(TicketOrderRequest request,
                                                     Integer beforeRedisStock,
                                                     Integer afterRedisStock,
                                                     StockSnapshot snapshot,
                                                     String resultMessage) {
        orderRequestMapper.markCompensateFailed(request.getId(), resultMessage);
        insertCompensationRecord(
                request.getTicketCategoryId(),
                request.getRequestId(),
                null,
                StockCompensationTypeEnum.RELEASE_FAILED_REQUEST_DEDUCTION.getCode(),
                beforeRedisStock,
                afterRedisStock,
                snapshot,
                request.getDeductedQuantity(),
                StockCompensationStatusEnum.FAILED.getCode(),
                resultMessage
        );
    }

    private void insertCompensationRecord(Long ticketCategoryId,
                                          String requestId,
                                          Long consistencyRecordId,
                                          String compensationType,
                                          Integer beforeRedisStock,
                                          Integer afterRedisStock,
                                          StockSnapshot snapshot,
                                          Integer delta,
                                          String status,
                                          String resultMessage) {
        LocalDateTime now = LocalDateTime.now();
        StockCompensationRecord record = new StockCompensationRecord();
        record.setTicketCategoryId(ticketCategoryId);
        record.setRequestId(requestId);
        record.setConsistencyRecordId(consistencyRecordId);
        record.setCompensationType(compensationType);
        record.setBeforeRedisStock(beforeRedisStock);
        record.setAfterRedisStock(afterRedisStock);
        record.setMysqlAvailableStock(snapshot == null ? null : snapshot.ticketStock().getAvailableStock());
        record.setInFlightDeductedQuantity(snapshot == null ? null : snapshot.inFlightDeductedQuantity());
        record.setExpectedRedisAvailableStock(snapshot == null ? null : snapshot.expectedRedisAvailableStock());
        record.setDelta(delta);
        record.setStatus(status);
        record.setResultMessage(trim(resultMessage));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        compensationRecordMapper.insert(record);
    }

    private int normalizeBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize <= 0) {
            return DEFAULT_BATCH_SIZE;
        }
        return Math.min(batchSize, MAX_BATCH_SIZE);
    }

    private int normalizeRecordLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_RECORD_LIMIT;
        }
        return Math.min(limit, MAX_RECORD_LIMIT);
    }

    private String trim(String message) {
        if (message == null || message.isBlank()) {
            return "库存补偿失败";
        }
        if (message.length() <= 512) {
            return message;
        }
        return message.substring(0, 512);
    }

    private record StockSnapshot(TicketStock ticketStock,
                                 Integer mysqlStockSum,
                                 boolean mysqlStockConsistent,
                                 Integer redisAvailableStock,
                                 Integer inFlightDeductedQuantity,
                                 Integer expectedRedisAvailableStock,
                                 Integer diff) {
    }
}
