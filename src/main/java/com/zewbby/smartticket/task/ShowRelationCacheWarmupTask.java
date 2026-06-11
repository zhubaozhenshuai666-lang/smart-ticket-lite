package com.zewbby.smartticket.task;

import com.zewbby.smartticket.config.ShowRelationCacheProperties;
import com.zewbby.smartticket.service.ShowRelationCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShowRelationCacheWarmupTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShowRelationCacheWarmupTask.class);

    private final ShowRelationCacheService showRelationCacheService;

    private final ShowRelationCacheProperties properties;

    public ShowRelationCacheWarmupTask(ShowRelationCacheService showRelationCacheService,
                                       ShowRelationCacheProperties properties) {
        this.showRelationCacheService = showRelationCacheService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        showRelationCacheService.refreshPublishedRelations();
    }

    @Scheduled(fixedDelayString = "#{@showRelationCacheProperties.refreshFixedDelayMillis}")
    public void refreshPeriodically() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            showRelationCacheService.refreshPublishedRelations();
        } catch (RuntimeException exception) {
            LOGGER.error("Show relation cache scheduled refresh failed", exception);
        }
    }
}
