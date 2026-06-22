package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.dto.RedisStockDeductResponse;
import com.zewbby.smartticket.enums.RedisStockDeductResult;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class StockLuaService {

    private final StringRedisTemplate stringRedisTemplate;

    private final RateLimitProperties rateLimitProperties;

    private final StockBucketProperties stockBucketProperties;

    private final DefaultRedisScript<Long> preDeductScript;

    private final DefaultRedisScript<Long> bucketPreDeductScript;

    private final DefaultRedisScript<Long> rollbackScript;

    private final DefaultRedisScript<Long> rollbackOnceScript;

    private final DefaultRedisScript<Long> releasePreDeductScript;

    private final DefaultRedisScript<Long> bucketReleasePreDeductScript;

    private final DefaultRedisScript<Long> repairCasDeltaScript;

    private static final long DEDUCTED_RECORD_TTL_SECONDS = 24 * 60 * 60L;

    private static final long ROLLBACK_MARKER_TTL_SECONDS = 7 * 24 * 60 * 60L;

    private static final String SOLDOUT_MARKER_VALUE = "1";

    @Autowired
    public StockLuaService(StringRedisTemplate stringRedisTemplate,
                           RateLimitProperties rateLimitProperties,
                           StockBucketProperties stockBucketProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitProperties = rateLimitProperties;
        this.stockBucketProperties = stockBucketProperties;
        this.preDeductScript = buildScript("lua/stock_pre_deduct.lua");
        this.bucketPreDeductScript = buildScript("lua/stock_bucket_pre_deduct.lua");
        this.rollbackScript = buildScript("lua/stock_rollback.lua");
        this.rollbackOnceScript = buildScript("lua/stock_rollback_once.lua");
        this.releasePreDeductScript = buildScript("lua/stock_release_pre_deduct.lua");
        this.bucketReleasePreDeductScript = buildScript("lua/stock_bucket_release_pre_deduct.lua");
        this.repairCasDeltaScript = buildScript("lua/stock_repair_cas_delta.lua");
    }

    public StockLuaService(StringRedisTemplate stringRedisTemplate,
                           RateLimitProperties rateLimitProperties) {
        this(stringRedisTemplate, rateLimitProperties, new StockBucketProperties());
    }

    StockLuaService(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, new RateLimitProperties(), new StockBucketProperties());
    }

    /**
     * 使用 requestId 作为幂等键预扣 Redis 库存。
     *
     * 这个方法解决的是高并发异步下单入口的第一道筛选问题：大量请求不能都打到 MySQL，
     * 也不能在 Java 里先读 Redis 库存再扣减，因为两个命令之间会被其他线程插队。
     * Lua 会在 Redis 单线程执行模型里一次完成“重复 requestId 判断、库存存在性判断、库存扣减、预扣记录写入”。
     *
     * @param requestId 当前异步下单请求号，同一个 requestId 只能预扣一次。
     * @param ticketCategoryId 票档 ID，用来定位 Redis 可抢库存 key。
     * @param quantity 本次购买数量，必须大于 0。
     * @return SUCCESS 表示 Redis 已经扣减库存；其他返回值表示没有扣减成功，业务层不能继续发送 MQ。
     */
    public RedisStockDeductResult preDeductStock(String requestId, Long ticketCategoryId, Integer quantity) {
        String stockKey = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        String deductedKey = RedisKeyConstant.stockDeductedRequestKey(requestId);
        String soldoutKey = RedisKeyConstant.stockSoldoutKey(ticketCategoryId);
        Long result = stringRedisTemplate.execute(
                preDeductScript,
                Arrays.asList(stockKey, deductedKey, soldoutKey),
                String.valueOf(quantity),
                String.valueOf(DEDUCTED_RECORD_TTL_SECONDS),
                String.valueOf(rateLimitProperties.getSoldoutTtlSeconds())
        );
        if (result == null) {
            throw new BusinessException("Redis库存预扣脚本执行失败");
        }
        RedisStockDeductResult deductResult = RedisStockDeductResult.fromCode(result);
        if (deductResult == RedisStockDeductResult.STOCK_NOT_ENOUGH) {
            stringRedisTemplate.opsForValue().set(
                    soldoutKey,
                    SOLDOUT_MARKER_VALUE,
                    Duration.ofSeconds(rateLimitProperties.getSoldoutTtlSeconds())
            );
        }
        return deductResult;
    }

    /**
     * 使用 bucket 分片预扣 Redis 库存。
     *
     * 单票档单 key 在热门抢票时会形成 Redis 热 key；bucket 分片把一个票档拆成多个 key。
     * 这里从 BucketRouteService 算出的 initialBucketNo 开始，只探测 activeProbeCount 个 bucket。
     * 小窗口未命中返回 PROBE_MISS，不能写全局售罄，也不能在 Java 层循环重试。
     * 注意：一个请求只允许扣一个 bucket，不能拆成多个 bucket，否则消费者落 MySQL 和失败补偿都会变复杂。
     *
     * @return result=SUCCESS 时 bucketNo 表示实际扣减的 bucket；失败时 bucketNo 为空。
     */
    public RedisStockDeductResponse preDeductBucketStock(String requestId,
                                                         Long ticketCategoryId,
                                                         Integer quantity,
                                                         Integer initialBucketNo,
                                                         Integer bucketCount) {
        return preDeductBucketStock(
                requestId,
                ticketCategoryId,
                quantity,
                stockBucketProperties.getActiveVersion(),
                initialBucketNo,
                bucketCount,
                stockBucketProperties.getActiveProbeCount()
        );
    }

    public RedisStockDeductResponse preDeductBucketStock(String requestId,
                                                         Long ticketCategoryId,
                                                         Integer quantity,
                                                         Integer bucketVersion,
                                                         Integer initialBucketNo,
                                                         Integer bucketCount,
                                                         Integer probeCount) {
        //校验参数合法性
        if (bucketCount == null || bucketCount <= 0) {
            bucketCount = stockBucketProperties.getDefaultBucketCount();
        }
        if (bucketVersion == null || bucketVersion <= 0) {
            bucketVersion = stockBucketProperties.getActiveVersion();
        }
        if (initialBucketNo == null || initialBucketNo < 0) {
            initialBucketNo = 0;
        }
        initialBucketNo = Math.floorMod(initialBucketNo, bucketCount);
        int attemptLimit = normalizeProbeCount(probeCount, bucketCount);

        //初始化key
        String deductedKey = RedisKeyConstant.stockDeductedRequestKey(requestId);
        List<String> keys = new ArrayList<>();
        keys.add(deductedKey);
        keys.add(RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, bucketVersion));
        for (int offset = 0; offset < attemptLimit; offset++) {
            int bucketNo = Math.floorMod(initialBucketNo + offset, bucketCount);
            keys.add(RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketVersion, bucketNo));
        }
        for (int offset = 0; offset < attemptLimit; offset++) {
            int bucketNo = Math.floorMod(initialBucketNo + offset, bucketCount);
            keys.add(RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketVersion, bucketNo));
        }

        Long result = stringRedisTemplate.execute(
                bucketPreDeductScript,
                keys,
                String.valueOf(quantity),
                String.valueOf(DEDUCTED_RECORD_TTL_SECONDS),
                String.valueOf(rateLimitProperties.getSoldoutTtlSeconds()),
                String.valueOf(ticketCategoryId),
                String.valueOf(bucketVersion),
                String.valueOf(initialBucketNo),
                String.valueOf(bucketCount),
                String.valueOf(attemptLimit)
        );
        if (result == null) {
            throw new BusinessException("Redis bucket库存预扣脚本执行失败");
        }
        RedisStockDeductResult deductResult = RedisStockDeductResult.fromCode(result);
        if (!deductResult.isSuccess()) {
            return new RedisStockDeductResponse(deductResult, null);
        }
        return new RedisStockDeductResponse(deductResult, parseBucketNoFromDeductedRecord(deductedKey));
    }

    private int normalizeProbeCount(Integer probeCount, int bucketCount) {
        if (probeCount == null || probeCount <= 0) {
            return 1;
        }
        return Math.min(probeCount, bucketCount);
    }

    /**
     * 兼容旧调用的预扣方法。
     *
     * 阶段 2 后，真正的异步下单必须传入 requestId 来防重复预扣；这个方法只保留给旧测试或临时调用。
     * 它会生成一个临时 requestId，因此不具备“同一个业务请求只扣一次”的能力，业务入口不要再使用它。
     */
    @Deprecated
    public boolean preDeductStock(Long ticketCategoryId, Integer quantity) {
        RedisStockDeductResult result = preDeductStock(
                "legacy:" + UUID.randomUUID().toString().replace("-", ""),
                ticketCategoryId,
                quantity
        );
        if (result.isSuccess()) {
            return true;
        }
        throw toPreDeductException(result);
    }

    /**
     * 回滚库存
     * @param ticketCategoryId
     * @param quantity
     */
    public void rollbackStock(Long ticketCategoryId, Integer quantity) {
        Long result = executeScript(rollbackScript, ticketCategoryId, quantity);
        if (result == -1L) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_PREHEATED);
        }
        if (result == -3L) {
            throw new BusinessException("Redis库存值不是整数，请重新预热库存");
        }
        if (result == -4L) {
            throw new BusinessException("Redis库存回滚数量非法");
        }
        if (result < 0L) {
            throw new BusinessException("Redis库存回滚失败");
        }
    }

    /**
     * 释放某个 requestId 的 Redis 预扣库存。
     *
     * Redis 预扣成功只说明入口层已经把“可抢库存”减掉，并不代表 MySQL 订单一定创建成功。
     * 如果后续插入请求、发送消息、消费者创建订单或 MySQL 条件扣库存失败，就必须把这部分 Redis 库存还回去。
     * 释放脚本同时检查 deducted key 和 compensated key，避免同一个失败请求被重复补偿导致 Redis 库存多加。
     *
     * @param requestId 异步下单请求号，用来找到预扣标记和补偿标记。
     * @param ticketCategoryId 票档 ID，用来定位 Redis 可抢库存 key。
     * @param quantity 需要还回 Redis 的数量。
     * @return SUCCESS 表示本次确实完成释放；ALREADY_COMPENSATED 表示此前已经释放过；NOT_DEDUCTED 表示没有找到预扣记录。
     */
    public RedisStockReleaseResult releasePreDeductedStock(String requestId,
                                                           Long ticketCategoryId,
                                                           Integer quantity) {
        String stockKey = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        String deductedKey = RedisKeyConstant.stockDeductedRequestKey(requestId);
        String compensatedKey = RedisKeyConstant.stockCompensatedRequestKey(requestId);
        Long result = stringRedisTemplate.execute(
                releasePreDeductScript,
                Arrays.asList(stockKey, deductedKey, compensatedKey),
                String.valueOf(quantity),
                String.valueOf(ROLLBACK_MARKER_TTL_SECONDS)
        );
        if (result == null) {
            throw new BusinessException("Redis预扣库存释放脚本执行失败");
        }
        return RedisStockReleaseResult.fromCode(result);
    }

    public RedisStockReleaseResult releasePreDeductedStock(String requestId,
                                                           Long ticketCategoryId,
                                                           Integer bucketNo,
                                                           Integer quantity) {
        return releasePreDeductedStock(requestId, ticketCategoryId, null, bucketNo, quantity);
    }

    public RedisStockReleaseResult releasePreDeductedStock(String requestId,
                                                           Long ticketCategoryId,
                                                           Integer bucketVersion,
                                                           Integer bucketNo,
                                                           Integer quantity) {
        if (bucketNo == null) {
            return releasePreDeductedStock(requestId, ticketCategoryId, quantity);
        }
        String stockKey = bucketVersion == null
                ? RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketNo)
                : RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketVersion, bucketNo);
        String deductedKey = RedisKeyConstant.stockDeductedRequestKey(requestId);
        String compensatedKey = RedisKeyConstant.stockCompensatedRequestKey(requestId);
        Long result = stringRedisTemplate.execute(
                bucketReleasePreDeductScript,
                Arrays.asList(
                        stockKey,
                        deductedKey,
                        compensatedKey,
                        bucketVersion == null
                                ? RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketNo)
                                : RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketVersion, bucketNo),
                        bucketVersion == null
                                ? RedisKeyConstant.stockSoldoutKey(ticketCategoryId)
                                : RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, bucketVersion)
                ),
                String.valueOf(ticketCategoryId),
                bucketVersion == null ? "" : String.valueOf(bucketVersion),
                String.valueOf(bucketNo),
                String.valueOf(quantity),
                String.valueOf(ROLLBACK_MARKER_TTL_SECONDS)
        );
        if (result == null) {
            throw new BusinessException("Redis bucket预扣库存释放脚本执行失败");
        }
        return RedisStockReleaseResult.fromCode(result);
    }

    public void rollbackBucketStock(Long ticketCategoryId, Integer bucketNo, Integer quantity) {
        rollbackBucketStock(ticketCategoryId, null, bucketNo, quantity);
    }

    public void rollbackBucketStock(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo, Integer quantity) {
        if (bucketNo == null) {
            rollbackStock(ticketCategoryId, quantity);
            return;
        }
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("Redis bucket库存回滚数量非法");
        }
        String key = bucketVersion == null
                ? RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketNo)
                : RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketVersion, bucketNo);
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_PREHEATED);
        }
        stringRedisTemplate.opsForValue().increment(key, quantity);
        if (bucketVersion == null) {
            stringRedisTemplate.delete(RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketNo));
            stringRedisTemplate.delete(RedisKeyConstant.stockSoldoutKey(ticketCategoryId));
        } else {
            stringRedisTemplate.delete(RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketVersion, bucketNo));
            stringRedisTemplate.delete(RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, bucketVersion));
        }
    }

    /**
     * 使用 Lua CAS + Delta 修复 Redis 可售库存。
     *
     * 正常售卖期间不能无保护 SET 覆盖 Redis 库存，因为 SET 会把检查之后发生的并发预扣全部抹掉。
     * 这里的修复语义是：“如果 Redis 当前值仍等于我刚刚检查到的 beforeRedisStock，就把差值 delta 加上去；
     * 如果 Redis 已被并发下单改动，则本次放弃，要求重新 check”。这样宁可少修一次，也不能把库存修坏。
     */
    public RedisStockRepairResult repairStockByCasDelta(Long ticketCategoryId,
                                                        Integer beforeRedisStock,
                                                        Integer delta) {
        String stockKey = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        Long result = stringRedisTemplate.execute(
                repairCasDeltaScript,
                Collections.singletonList(stockKey),
                String.valueOf(beforeRedisStock),
                String.valueOf(delta)
        );
        if (result == null) {
            throw new BusinessException("Redis库存修复脚本执行失败");
        }
        return RedisStockRepairResult.fromCode(result);
    }

    public void rollbackStockOnce(String requestId, Long ticketCategoryId, Integer quantity) {
        //生成一个redis的key
        String stockKey = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        //生成回滚标记的key，防重
        String rollbackMarkerKey = RedisKeyConstant.stockRollbackRequestKey(requestId);
        //执行这个lua脚本
        Long result = stringRedisTemplate.execute(
                rollbackOnceScript,
                Arrays.asList(stockKey, rollbackMarkerKey),
                String.valueOf(quantity),
                String.valueOf(ROLLBACK_MARKER_TTL_SECONDS)
        );
        if (result == null) {
            throw new BusinessException("Redis库存回滚脚本执行失败");
        }
        if (result == -2L) {
            return;
        }
        if (result == -1L) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_PREHEATED);
        }
        if (result == -3L) {
            throw new BusinessException("Redis库存值不是整数，请重新预热库存");
        }
        if (result == -4L) {
            throw new BusinessException("Redis库存回滚数量非法");
        }
        if (result == -5L) {
            throw new BusinessException("Redis库存回滚标记过期时间非法");
        }
        if (result < 0L) {
            throw new BusinessException("Redis库存回滚失败");
        }
    }

    /**
     * 执行lua脚本
     * @param script
     * @param ticketCategoryId
     * @param quantity
     * @return
     */
    private Long executeScript(DefaultRedisScript<Long> script, Long ticketCategoryId, Integer quantity) {
        //取key
        String key = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(quantity)
        );
        if (result == null) {
            throw new BusinessException("Redis库存脚本执行失败");
        }
        return result;
    }

    private BusinessException toPreDeductException(RedisStockDeductResult result) {
        if (result == RedisStockDeductResult.STOCK_NOT_FOUND) {
            return new BusinessException(ErrorMessageConstant.STOCK_NOT_PREHEATED);
        }
        if (result == RedisStockDeductResult.STOCK_NOT_ENOUGH) {
            return new BusinessException(ErrorMessageConstant.STOCK_NOT_ENOUGH);
        }
        if (result == RedisStockDeductResult.DUPLICATE) {
            return new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }
        if (result == RedisStockDeductResult.INVALID_QUANTITY) {
            return new BusinessException("Redis库存扣减数量非法");
        }
        if (result == RedisStockDeductResult.STOCK_VALUE_INVALID) {
            return new BusinessException("Redis库存值不是整数，请重新预热库存");
        }
        if (result == RedisStockDeductResult.BUCKET_NOT_FOUND) {
            return new BusinessException("Redis库存bucket不存在，请重新预热库存");
        }
        if (result == RedisStockDeductResult.PROBE_MISS) {
            return new BusinessException(ErrorMessageConstant.ORDER_QUEUE_BUSY);
        }
        return new BusinessException("Redis库存预扣失败");
    }

    private Integer parseBucketNoFromDeductedRecord(String deductedKey) {
        String value = stringRedisTemplate.opsForValue().get(deductedKey);
        if (value == null || value.isBlank()) {
            throw new BusinessException("Redis bucket预扣记录不存在");
        }
        String[] parts = value.split(":");
        if (parts.length < 3) {
            throw new BusinessException("Redis bucket预扣记录格式非法");
        }
        if (parts.length >= 4 && parts[1].startsWith("v")) {
            return Integer.valueOf(parts[2]);
        }
        return Integer.valueOf(parts[1]);
    }

    /**
     * 负责加载和初始化 Lua 脚本
     * @param path
     * @return
     */
    private DefaultRedisScript<Long> buildScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
