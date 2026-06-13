package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.OrderSnapshotCacheProperties;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import com.zewbby.smartticket.domain.dto.OrderSnapshotRecord;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OrderSnapshotCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderSnapshotCacheService.class);

    private final TicketCategoryMapper ticketCategoryMapper;

    private final OrderSnapshotCacheProperties properties;

    private final AtomicReference<Map<SnapshotKey, OrderSnapshot>> snapshotMap = new AtomicReference<>(Map.of());

    private final AtomicLong snapshotVersion = new AtomicLong();

    private volatile boolean lastRefreshSuccessful;

    private volatile LocalDateTime lastRefreshAt;

    public OrderSnapshotCacheService(TicketCategoryMapper ticketCategoryMapper,
                                     OrderSnapshotCacheProperties properties) {
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.properties = properties;
    }

    public OrderSnapshot getPublishedSnapshot(Long showId, Long sessionId, Long ticketCategoryId) {
        if (!properties.isEnabled()) {
            return ticketCategoryMapper.selectOrderSnapshot(showId, sessionId, ticketCategoryId);
        }
        if (showId == null || sessionId == null || ticketCategoryId == null) {
            return null;
        }
        ensureReady();
        OrderSnapshot snapshot = snapshotMap.get().get(new SnapshotKey(showId, sessionId, ticketCategoryId));
        return copy(snapshot);
    }

    public void refreshPublishedSnapshots() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<OrderSnapshotRecord> records = ticketCategoryMapper.selectPublishedOrderSnapshots();
            Map<SnapshotKey, OrderSnapshot> nextSnapshot = new HashMap<>(Math.max(records.size() * 2, 16));
            for (OrderSnapshotRecord record : records) {
                if (record.getShowId() == null
                        || record.getSessionId() == null
                        || record.getTicketCategoryId() == null) {
                    continue;
                }
                nextSnapshot.put(
                        new SnapshotKey(record.getShowId(), record.getSessionId(), record.getTicketCategoryId()),
                        toOrderSnapshot(record)
                );
            }
            snapshotMap.set(Map.copyOf(nextSnapshot));
            long version = snapshotVersion.incrementAndGet();
            lastRefreshAt = LocalDateTime.now();
            lastRefreshSuccessful = true;
            LOGGER.info("Refreshed order snapshot cache, snapshotCount={}, version={}", nextSnapshot.size(), version);
        } catch (RuntimeException exception) {
            lastRefreshAt = LocalDateTime.now();
            lastRefreshSuccessful = false;
            LOGGER.error("Failed to refresh order snapshot cache, oldSnapshotCount={}, version={}",
                    snapshotMap.get().size(), snapshotVersion.get(), exception);
            if (properties.isFailClosed() && snapshotMap.get().isEmpty()) {
                throw exception;
            }
        }
    }

    public long snapshotCount() {
        return snapshotMap.get().size();
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

    private void ensureReady() {
        if (!properties.isFailClosed()) {
            return;
        }
        if (!lastRefreshSuccessful && snapshotMap.get().isEmpty()) {
            throw new BusinessException("订单快照缓存未就绪");
        }
    }

    private OrderSnapshot toOrderSnapshot(OrderSnapshotRecord record) {
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setShowTitle(record.getShowTitle());
        snapshot.setSessionStartTime(record.getSessionStartTime());
        snapshot.setTicketCategoryName(record.getTicketCategoryName());
        snapshot.setTicketPrice(record.getTicketPrice());
        return snapshot;
    }

    private OrderSnapshot copy(OrderSnapshot source) {
        if (source == null) {
            return null;
        }
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setShowTitle(source.getShowTitle());
        snapshot.setSessionStartTime(source.getSessionStartTime());
        snapshot.setTicketCategoryName(source.getTicketCategoryName());
        snapshot.setTicketPrice(source.getTicketPrice());
        return snapshot;
    }

    private record SnapshotKey(Long showId, Long sessionId, Long ticketCategoryId) {
    }
}
