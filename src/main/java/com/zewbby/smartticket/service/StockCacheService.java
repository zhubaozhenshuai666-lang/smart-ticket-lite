package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockCacheService {

    private final TicketStockMapper ticketStockMapper;

    private final StringRedisTemplate stringRedisTemplate;

    public StockCacheService(TicketStockMapper ticketStockMapper,
                             StringRedisTemplate stringRedisTemplate) {
        this.ticketStockMapper = ticketStockMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将 MySQL 的可售库存预热到 Redis。
     *
     * Redis 预扣库存依赖这个 key 作为入口库存快照；如果没有预热，Lua 会拒绝下单，避免把未知库存当成可售。
     * 注意：预热会用 MySQL available_stock 覆盖 Redis 当前值。如果票档正在高并发售卖，
     * 随意执行预热可能覆盖已经被 Redis 预扣掉的数量，导致 Redis 与 MySQL 语义短暂不一致。
     * 当前接口只适合本地开发、压测前初始化或明确停卖窗口使用，后续应迁移到更受控的 Admin 操作。
     *
     * @param ticketCategoryId 票档 ID。
     * @return 写入 Redis 后的可售库存。
     */
    public Integer preloadStock(Long ticketCategoryId) {
        TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(ticketCategoryId);
        if (ticketStock == null) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_FOUND);
        }
        setAvailableStock(ticketCategoryId, ticketStock.getAvailableStock());
        clearSoldout(ticketCategoryId);
        return ticketStock.getAvailableStock();
    }

    public void preloadAllStock() {
        List<TicketStock> ticketStocks = ticketStockMapper.selectAll();
        for (TicketStock ticketStock : ticketStocks) {
            setAvailableStock(ticketStock.getTicketCategoryId(), ticketStock.getAvailableStock());
            clearSoldout(ticketStock.getTicketCategoryId());
        }
    }

    /**
     * 判断票档是否命中 soldout 快速失败标记。
     *
     * soldout 只是一层热点保护：它告诉入口“最近 Redis 预扣已经判定库存不足”，让售罄后的请求不要继续创建
     * order_request、local_message 或进入 MQ。它不能替代真实库存判断，因为补偿、预热或人工调库存后库存可能恢复。
     */
    public boolean isSoldOut(Long ticketCategoryId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.stockSoldoutKey(ticketCategoryId)));
    }

    public boolean isSoldOut(Long ticketCategoryId, Integer bucketVersion) {
        if (bucketVersion == null || bucketVersion <= 0) {
            return isSoldOut(ticketCategoryId);
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, bucketVersion)));
    }

    public void clearSoldout(Long ticketCategoryId) {
        stringRedisTemplate.delete(RedisKeyConstant.stockSoldoutKey(ticketCategoryId));
    }

    public void clearVersionSoldout(Long ticketCategoryId, Integer bucketVersion) {
        if (bucketVersion == null || bucketVersion <= 0) {
            clearSoldout(ticketCategoryId);
            return;
        }
        stringRedisTemplate.delete(RedisKeyConstant.stockVersionSoldoutKey(ticketCategoryId, bucketVersion));
    }

    public void clearBucketSoldout(Long ticketCategoryId, Integer bucketNo) {
        stringRedisTemplate.delete(RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketNo));
    }

    public void clearBucketSoldout(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo) {
        if (bucketVersion == null || bucketVersion <= 0) {
            clearBucketSoldout(ticketCategoryId, bucketNo);
            return;
        }
        stringRedisTemplate.delete(RedisKeyConstant.stockBucketSoldoutKey(ticketCategoryId, bucketVersion, bucketNo));
    }

    public void clearAllBucketSoldout(Long ticketCategoryId, Integer bucketCount) {
        clearSoldout(ticketCategoryId);
        if (bucketCount == null || bucketCount <= 0) {
            return;
        }
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            clearBucketSoldout(ticketCategoryId, bucketNo);
        }
    }

    public void clearAllBucketSoldout(Long ticketCategoryId, Integer bucketVersion, Integer bucketCount) {
        if (bucketVersion == null || bucketVersion <= 0) {
            clearAllBucketSoldout(ticketCategoryId, bucketCount);
            return;
        }
        clearVersionSoldout(ticketCategoryId, bucketVersion);
        if (bucketCount == null || bucketCount <= 0) {
            return;
        }
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            clearBucketSoldout(ticketCategoryId, bucketVersion, bucketNo);
        }
    }

    public void clearSoldoutIfStockPositive(Long ticketCategoryId, Integer availableStock) {
        if (availableStock != null && availableStock > 0) {
            clearSoldout(ticketCategoryId);
        }
    }

    public Integer getAvailableStock(Long ticketCategoryId) {
        String value = stringRedisTemplate.opsForValue().get(RedisKeyConstant.stockAvailableKey(ticketCategoryId));
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value);
    }

    public Integer getBucketAvailableStock(Long ticketCategoryId, Integer bucketNo) {
        String value = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketNo));
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value);
    }

    public Integer getBucketAvailableStock(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo) {
        if (bucketVersion == null || bucketVersion <= 0) {
            return getBucketAvailableStock(ticketCategoryId, bucketNo);
        }
        String value = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketVersion, bucketNo));
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value);
    }

    public Integer sumBucketAvailableStock(Long ticketCategoryId, Integer bucketCount) {
        if (bucketCount == null || bucketCount <= 0) {
            return null;
        }
        int total = 0;
        boolean hasAnyBucket = false;
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            Integer bucketStock = getBucketAvailableStock(ticketCategoryId, bucketNo);
            if (bucketStock != null) {
                hasAnyBucket = true;
                total += bucketStock;
            }
        }
        return hasAnyBucket ? total : null;
    }

    public Integer sumBucketAvailableStock(Long ticketCategoryId, Integer bucketVersion, Integer bucketCount) {
        if (bucketVersion == null || bucketVersion <= 0) {
            return sumBucketAvailableStock(ticketCategoryId, bucketCount);
        }
        if (bucketCount == null || bucketCount <= 0) {
            return null;
        }
        int total = 0;
        boolean hasAnyBucket = false;
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            Integer bucketStock = getBucketAvailableStock(ticketCategoryId, bucketVersion, bucketNo);
            if (bucketStock != null) {
                hasAnyBucket = true;
                total += bucketStock;
            }
        }
        return hasAnyBucket ? total : null;
    }

    public void setAvailableStock(Long ticketCategoryId, Integer availableStock) {
        if (availableStock == null) {
            throw new BusinessException("可售库存不能为空");
        }
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.stockAvailableKey(ticketCategoryId), String.valueOf(availableStock));
    }

    public void setBucketAvailableStock(Long ticketCategoryId, Integer bucketNo, Integer availableStock) {
        if (availableStock == null) {
            throw new BusinessException("bucket可售库存不能为空");
        }
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketNo), String.valueOf(availableStock));
    }

    public void setBucketAvailableStock(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo, Integer availableStock) {
        if (bucketVersion == null || bucketVersion <= 0) {
            setBucketAvailableStock(ticketCategoryId, bucketNo, availableStock);
            return;
        }
        if (availableStock == null) {
            throw new BusinessException("bucket可售库存不能为空");
        }
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.stockBucketAvailableKey(ticketCategoryId, bucketVersion, bucketNo), String.valueOf(availableStock));
    }

    public void setBucketCount(Long ticketCategoryId, Integer bucketCount) {
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.stockBucketCountKey(ticketCategoryId), String.valueOf(bucketCount));
    }

    public void setBucketCount(Long ticketCategoryId, Integer bucketVersion, Integer bucketCount) {
        if (bucketVersion == null || bucketVersion <= 0) {
            setBucketCount(ticketCategoryId, bucketCount);
            return;
        }
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.stockBucketCountKey(ticketCategoryId, bucketVersion), String.valueOf(bucketCount));
    }

    public void increaseAvailableStock(Long ticketCategoryId, Integer quantity) {
        String key = RedisKeyConstant.stockAvailableKey(ticketCategoryId);
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ErrorMessageConstant.STOCK_NOT_PREHEATED);
        }
        stringRedisTemplate.opsForValue().increment(key, quantity);
    }
}
