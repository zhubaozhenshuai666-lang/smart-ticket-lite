package com.zewbby.smartticket.task;

import com.zewbby.smartticket.config.OrderSnapshotCacheProperties;
import com.zewbby.smartticket.service.OrderSnapshotCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderSnapshotCacheWarmupTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderSnapshotCacheWarmupTask.class);

    private final OrderSnapshotCacheService orderSnapshotCacheService;

    private final OrderSnapshotCacheProperties properties;

    public OrderSnapshotCacheWarmupTask(OrderSnapshotCacheService orderSnapshotCacheService,
                                        OrderSnapshotCacheProperties properties) {
        this.orderSnapshotCacheService = orderSnapshotCacheService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        orderSnapshotCacheService.refreshPublishedSnapshots();
    }

    @Scheduled(fixedDelayString = "#{@orderSnapshotCacheProperties.refreshFixedDelayMillis}")
    public void refreshPeriodically() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            orderSnapshotCacheService.refreshPublishedSnapshots();
        } catch (RuntimeException exception) {
            LOGGER.error("Order snapshot cache scheduled refresh failed", exception);
        }
    }
}
