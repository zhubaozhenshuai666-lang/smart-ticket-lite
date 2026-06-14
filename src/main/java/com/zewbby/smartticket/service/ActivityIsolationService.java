package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.ActivityIsolationProperties;
import com.zewbby.smartticket.domain.dto.ActivityScope;
import org.springframework.stereotype.Service;

@Service
public class ActivityIsolationService {

    private final ActivityIsolationProperties properties;

    public ActivityIsolationService(ActivityIsolationProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public long maxInFlight(ActivityScope activityScope, Long ticketCategoryId) {
        return properties.getMaxInFlightPerActivityTicketCategory();
    }

    public String scopeKey(ActivityScope activityScope) {
        if (activityScope == null || activityScope.scopeKey() == null || activityScope.scopeKey().isBlank()) {
            return "unknown";
        }
        return activityScope.scopeKey();
    }
}
