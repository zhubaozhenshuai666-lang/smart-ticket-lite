package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.dto.StockBucketPorterResult;
import com.zewbby.smartticket.domain.entity.TicketStockBucket;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.StockBucketPorterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class StockBucketPorterServiceImpl implements StockBucketPorterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockBucketPorterServiceImpl.class);

    private static final long MOVE_SUCCESS = 1L;

    private final StringRedisTemplate stringRedisTemplate;

    private final TicketStockBucketMapper ticketStockBucketMapper;

    private final StockBucketProperties stockBucketProperties;

    private final ObservabilityMetricsService observabilityMetricsService;

    private final TransactionTemplate transactionTemplate;

    private final DefaultRedisScript<Long> porterMoveScript;

    private final DefaultRedisScript<Long> lockReleaseScript;

    public StockBucketPorterServiceImpl(StringRedisTemplate stringRedisTemplate,
                                        TicketStockBucketMapper ticketStockBucketMapper,
                                        StockBucketProperties stockBucketProperties,
                                        ObservabilityMetricsService observabilityMetricsService,
                                        TransactionTemplate transactionTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ticketStockBucketMapper = ticketStockBucketMapper;
        this.stockBucketProperties = stockBucketProperties;
        this.observabilityMetricsService = observabilityMetricsService;
        this.transactionTemplate = transactionTemplate;
        this.porterMoveScript = buildScript("lua/stock_bucket_porter_move.lua");
        this.lockReleaseScript = buildScript("lua/redis_lock_release.lua");
    }

    /**
     * 将旧版本回收站里的可售库存搬运到新版本 bucket 池。
     *
     * Porter 只搬 available_stock，不碰 locked/sold。旧版本仍负责承接旧订单取消/超时回滚；
     * 回滚产生的可售库存会被本方法逐步搬到 active version，让新请求更容易命中。
     */
    @Override
    public StockBucketPorterResult moveReturnedStock(Long ticketCategoryId,
                                                     Integer fromVersion,
                                                     Integer toVersion,
                                                     Integer fromBucketCount,
                                                     Integer toBucketCount,
                                                     Integer maxMoveQuantity) {
        validateArguments(ticketCategoryId, fromVersion, toVersion);
        int normalizedFromBucketCount = normalizePositive(fromBucketCount, stockBucketProperties.getDefaultBucketCount());
        int normalizedToBucketCount = normalizePositive(toBucketCount, stockBucketProperties.getTailBucketCount());
        int remainingMoveQuantity = normalizePositive(maxMoveQuantity, stockBucketProperties.getPorterMaxMoveQuantityPerRun());
        String lockKey = RedisKeyConstant.stockBucketPorterLockKey(ticketCategoryId, fromVersion, toVersion);
        String lockToken = UUID.randomUUID().toString();

        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                Duration.ofSeconds(stockBucketProperties.getPorterLockTtlSeconds())
        );
        if (!Boolean.TRUE.equals(locked)) {
            observabilityMetricsService.recordStockBucketPorterLockSkipped();
            return StockBucketPorterResult.lockSkipped(ticketCategoryId, fromVersion, toVersion);
        }

        int movedBucketCount = 0;
        int movedQuantity = 0;
        try {
            assertTargetBucketsReady(ticketCategoryId, toVersion, normalizedToBucketCount);
            List<TicketStockBucket> sourceBuckets = ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(
                    ticketCategoryId,
                    fromVersion
            );
            for (TicketStockBucket sourceBucket : sourceBuckets) {
                if (remainingMoveQuantity <= 0) {
                    break;
                }
                int sourceAvailable = positiveValue(sourceBucket.getAvailableStock());
                if (sourceAvailable <= 0) {
                    continue;
                }
                int quantity = Math.min(sourceAvailable, remainingMoveQuantity);
                int targetBucketNo = Math.floorMod(sourceBucket.getBucketNo(), normalizedToBucketCount);

                moveOneBucketInNewTransaction(
                        ticketCategoryId,
                        fromVersion,
                        sourceBucket.getBucketNo(),
                        toVersion,
                        targetBucketNo,
                        quantity
                );
                movedBucketCount++;
                movedQuantity += quantity;
                remainingMoveQuantity -= quantity;
            }
            observabilityMetricsService.recordStockBucketPorterMoved(movedQuantity);
            LOGGER.info("Moved returned bucket stock, ticketCategoryId={}, fromVersion={}, toVersion={}, movedBucketCount={}, movedQuantity={}",
                    ticketCategoryId, fromVersion, toVersion, movedBucketCount, movedQuantity);
            return StockBucketPorterResult.completed(
                    ticketCategoryId,
                    fromVersion,
                    toVersion,
                    movedBucketCount,
                    movedQuantity
            );
        } catch (RuntimeException exception) {
            observabilityMetricsService.recordStockBucketPorterFailed();
            throw exception;
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

    private void moveOneBucketInNewTransaction(Long ticketCategoryId,
                                               Integer fromVersion,
                                               Integer fromBucketNo,
                                               Integer toVersion,
                                               Integer toBucketNo,
                                               Integer quantity) {
        transactionTemplate.executeWithoutResult(status -> moveOneBucket(
                ticketCategoryId,
                fromVersion,
                fromBucketNo,
                toVersion,
                toBucketNo,
                quantity
        ));
    }

    private void moveOneBucket(Long ticketCategoryId,
                               Integer fromVersion,
                               Integer fromBucketNo,
                               Integer toVersion,
                               Integer toBucketNo,
                               Integer quantity) {
        int sourceRows = ticketStockBucketMapper.adjustAvailableStockByVersion(
                ticketCategoryId,
                fromVersion,
                fromBucketNo,
                -quantity
        );
        if (sourceRows != 1) {
            throw new BusinessException("Porter来源bucket库存不足，无法搬运");
        }
        int targetRows = ticketStockBucketMapper.adjustAvailableStockByVersion(
                ticketCategoryId,
                toVersion,
                toBucketNo,
                quantity
        );
        if (targetRows != 1) {
            throw new BusinessException("Porter目标bucket不存在，无法搬运");
        }

        String moveId = UUID.randomUUID().toString();
        Long result = stringRedisTemplate.execute(
                porterMoveScript,
                Arrays.asList(
                        RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, fromVersion, fromBucketNo),
                        RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, toVersion, toBucketNo),
                        RedisKeyConstant.stockBucketPorterMoveKey(
                                ticketCategoryId,
                                fromVersion,
                                fromBucketNo,
                                toVersion,
                                toBucketNo,
                                moveId
                        ),
                        RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, toVersion, toBucketNo),
                        RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, toVersion)
                ),
                String.valueOf(quantity),
                String.valueOf(stockBucketProperties.getPorterMoveRecordTtlSeconds())
        );
        if (result == null) {
            throw new BusinessException("Porter Redis搬运脚本执行失败");
        }
        if (result != MOVE_SUCCESS) {
            throw new BusinessException("Porter Redis搬运失败，返回码=" + result);
        }
    }

    private void assertTargetBucketsReady(Long ticketCategoryId, Integer toVersion, int toBucketCount) {
        int actualCount = ticketStockBucketMapper.countByTicketCategoryIdAndVersion(ticketCategoryId, toVersion);
        if (actualCount < toBucketCount) {
            throw new BusinessException("Porter目标版本bucket未初始化");
        }
    }

    private void releaseLock(String lockKey, String lockToken) {
        stringRedisTemplate.execute(
                lockReleaseScript,
                java.util.Collections.singletonList(lockKey),
                lockToken
        );
    }

    private void validateArguments(Long ticketCategoryId, Integer fromVersion, Integer toVersion) {
        if (ticketCategoryId == null || ticketCategoryId <= 0) {
            throw new BusinessException("Porter票档ID非法");
        }
        if (fromVersion == null || fromVersion <= 0 || toVersion == null || toVersion <= 0) {
            throw new BusinessException("Porter bucket版本非法");
        }
        if (fromVersion.equals(toVersion)) {
            throw new BusinessException("Porter来源版本和目标版本不能相同");
        }
    }

    private int normalizePositive(Integer value, int defaultValue) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return value;
    }

    private int positiveValue(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private DefaultRedisScript<Long> buildScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
