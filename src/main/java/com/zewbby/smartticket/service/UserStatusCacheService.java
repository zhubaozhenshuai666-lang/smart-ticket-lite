package com.zewbby.smartticket.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zewbby.smartticket.config.UserStatusCacheProperties;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.enums.UserStatusEnum;
import com.zewbby.smartticket.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserStatusCacheService {

    private final UserMapper userMapper;

    private final UserStatusCacheProperties properties;

    private final Cache<Long, Boolean> normalUserCache;

    public UserStatusCacheService(UserMapper userMapper, UserStatusCacheProperties properties) {
        this.userMapper = userMapper;
        this.properties = properties;
        this.normalUserCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1L, properties.getMaximumSize()))
                .expireAfterWrite(Duration.ofSeconds(Math.max(1L, properties.getExpireAfterWriteSeconds())))
                .build();
    }

    public boolean isNormalUser(Long userId) {
        if (userId == null) {
            return false;
        }
        if (!properties.isEnabled()) {
            return queryNormalUser(userId);
        }
        Boolean cached = normalUserCache.get(userId, this::queryNormalUser);
        return Boolean.TRUE.equals(cached);
    }

    public void invalidate(Long userId) {
        if (userId != null) {
            normalUserCache.invalidate(userId);
        }
    }

    private boolean queryNormalUser(Long userId) {
        UserAccount user = userMapper.selectById(userId);
        return user != null && UserStatusEnum.isNormal(user.getStatus());
    }
}
