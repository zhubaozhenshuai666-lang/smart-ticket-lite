package com.zewbby.smartticket.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.ShowRelationCacheProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.ShowRelationRecord;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ShowRelationCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShowRelationCacheService.class);

    private final TicketCategoryMapper ticketCategoryMapper;

    private final ShowRelationCacheProperties properties;

    private final AtomicReference<Set<RelationKey>> relationSnapshot = new AtomicReference<>(Set.of());

    private final AtomicLong snapshotVersion = new AtomicLong();

    private final Cache<RelationKey, Boolean> lookupCache;

    private volatile boolean lastRefreshSuccessful;

    private volatile LocalDateTime lastRefreshAt;

    public ShowRelationCacheService(TicketCategoryMapper ticketCategoryMapper,
                                    ShowRelationCacheProperties properties) {
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.properties = properties;
        this.lookupCache = Caffeine.newBuilder()
                .maximumSize(properties.getLookupCacheMaximumSize())
                .recordStats()
                .build();
    }

    public void validatePublishedRelation(Long showId, Long sessionId, Long ticketCategoryId) {
        if (!existsPublishedRelation(showId, sessionId, ticketCategoryId)) {
            throw new BusinessException(ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
        }
    }

    public boolean existsPublishedRelation(Long showId, Long sessionId, Long ticketCategoryId) {
        if (!properties.isEnabled()) {
            return ticketCategoryMapper.existsShowSessionTicketCategoryRelation(showId, sessionId, ticketCategoryId);
        }
        if (showId == null || sessionId == null || ticketCategoryId == null) {
            return false;
        }
        ensureReady();
        RelationKey key = new RelationKey(showId, sessionId, ticketCategoryId);
        return lookupCache.get(key, relationSnapshot.get()::contains);
    }

    public void refreshPublishedRelations() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<ShowRelationRecord> records = ticketCategoryMapper.selectPublishedShowRelations();
            Set<RelationKey> nextSnapshot = new HashSet<>(Math.max(records.size() * 2, 16));
            for (ShowRelationRecord record : records) {
                if (record.getShowId() == null
                        || record.getSessionId() == null
                        || record.getTicketCategoryId() == null) {
                    continue;
                }
                nextSnapshot.add(new RelationKey(
                        record.getShowId(),
                        record.getSessionId(),
                        record.getTicketCategoryId()
                ));
            }
            relationSnapshot.set(Collections.unmodifiableSet(nextSnapshot));
            lookupCache.invalidateAll();
            long version = snapshotVersion.incrementAndGet();
            lastRefreshAt = LocalDateTime.now();
            lastRefreshSuccessful = true;
            LOGGER.info("Refreshed show relation cache, relationCount={}, version={}", nextSnapshot.size(), version);
        } catch (RuntimeException exception) {
            lastRefreshAt = LocalDateTime.now();
            lastRefreshSuccessful = false;
            LOGGER.error("Failed to refresh show relation cache, oldRelationCount={}, version={}",
                    relationSnapshot.get().size(), snapshotVersion.get(), exception);
            if (properties.isFailClosed() && relationSnapshot.get().isEmpty()) {
                throw exception;
            }
        }
    }

    public long relationCount() {
        return relationSnapshot.get().size();
    }

    public long snapshotVersion() {
        return snapshotVersion.get();
    }

    public boolean isLastRefreshSuccessful() {
        return lastRefreshSuccessful;
    }

    public LocalDateTime getLastRefreshAt() {
        return lastRefreshAt;
    }

    public CacheStatsSnapshot cacheStats() {
        var stats = lookupCache.stats();
        return new CacheStatsSnapshot(stats.hitCount(), stats.missCount(), stats.evictionCount());
    }

    private void ensureReady() {
        if (!properties.isFailClosed()) {
            return;
        }
        if (!lastRefreshSuccessful && relationSnapshot.get().isEmpty()) {
            throw new BusinessException("演出关系缓存未就绪");
        }
    }

    private record RelationKey(Long showId, Long sessionId, Long ticketCategoryId) {
    }

    public record CacheStatsSnapshot(long hitCount, long missCount, long evictionCount) {
    }
}
