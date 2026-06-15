package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.ShowCacheProperties;
import com.zewbby.smartticket.service.CacheService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.ShowListVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;
import com.zewbby.smartticket.mapper.ShowMapper;
import com.zewbby.smartticket.service.ShowService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ShowServiceImpl implements ShowService {

    //初始化构造一个showMapper
    private final ShowMapper showMapper;

    private final CacheService cacheService;

    private final ShowCacheProperties showCacheProperties;

    public ShowServiceImpl(ShowMapper showMapper,
                           CacheService cacheService,
                           ShowCacheProperties showCacheProperties) {
        this.showMapper = showMapper;
        this.cacheService = cacheService;
        this.showCacheProperties = showCacheProperties;
    }

    /**
     * 打印
     * @return
     */
    @Override
    public List<ShowListVO> listShows() {
        return showMapper.selectShowList();
    }

    /**
     * 获取演出详情
     * @param showId
     * @return
     */
    @Override
    public ShowDetailVO getShowDetail(Long showId) {
        //生成key
        String cacheKey = RedisKeyConstant.showDetailKey(showId);
        Object cached = cacheService.getRaw(cacheKey);
        if (cacheService.isNullValue(cached)) {
            throw new BusinessException("演出不存在");
        }
        if (cached != null) {
            return (ShowDetailVO) cached;
        }

        return rebuildWithMutex(
                cacheKey,
                () -> {
                    Object latest = cacheService.getRaw(cacheKey);
                    if (cacheService.isNullValue(latest)) {
                        throw new BusinessException("演出不存在");
                    }
                    if (latest != null) {
                        return (ShowDetailVO) latest;
                    }
                    ShowDetailVO showDetailVO = showMapper.selectShowDetailById(showId);
                    if (showDetailVO == null) {
                        cacheService.setNullValue(cacheKey, nullTtl(), nullTtlJitter());
                        throw new BusinessException("演出不存在");
                    }
                    List<SessionVO> sessions = loadSessionsFromDatabase(showId);
                    showDetailVO.setSessions(sessions);
                    cacheService.set(
                            RedisKeyConstant.showSessionsKey(showId),
                            sessions,
                            showSessionsTtl(),
                            showSessionsTtlJitter()
                    );
                    cacheService.set(cacheKey, showDetailVO, showDetailTtl(), showDetailTtlJitter());
                    return showDetailVO;
                },
                () -> getShowDetailFromCacheAfterRebuild(cacheKey)
        );
    }

    /**
     * 查所有场次信息
     * @param showId
     * @return
     */
    @Override
    public List<SessionVO> listSessions(Long showId) {
        //cache
        String cacheKey = RedisKeyConstant.showSessionsKey(showId);
        Object cached = cacheService.getRaw(cacheKey);
        if (cacheService.isNullValue(cached)) {
            throw new BusinessException("演出不存在");
        }
        if (cached != null) {
            return (List<SessionVO>) cached;
        }

        return rebuildWithMutex(
                cacheKey,
                () -> {
                    Object latest = cacheService.getRaw(cacheKey);
                    if (cacheService.isNullValue(latest)) {
                        throw new BusinessException("演出不存在");
                    }
                    if (latest != null) {
                        return (List<SessionVO>) latest;
                    }
                    if (showMapper.selectShowDetailById(showId) == null) {
                        cacheService.setNullValue(cacheKey, nullTtl(), nullTtlJitter());
                        throw new BusinessException("演出不存在");
                    }
                    List<SessionVO> sessions = loadSessionsFromDatabase(showId);
                    cacheService.set(cacheKey, sessions, showSessionsTtl(), showSessionsTtlJitter());
                    return sessions;
                },
                () -> getSessionsFromCacheAfterRebuild(cacheKey)
        );
    }

    /**
     * 查票档信息
     * @param sessionId
     * @return
     */
    @Override
    public List<TicketCategoryVO> listTicketCategories(Long sessionId) {
        String cacheKey = RedisKeyConstant.sessionTicketCategoriesKey(sessionId);
        Object cached = cacheService.getRaw(cacheKey);
        if (cached != null) {
            return (List<TicketCategoryVO>) cached;
        }

        return rebuildWithMutex(
                cacheKey,
                () -> {
                    Object latest = cacheService.getRaw(cacheKey);
                    if (latest != null) {
                        return (List<TicketCategoryVO>) latest;
                    }
                    List<TicketCategoryVO> ticketCategories = loadTicketCategoriesFromDatabase(sessionId);
                    cacheService.set(
                            cacheKey,
                            ticketCategories,
                            sessionTicketCategoriesTtl(),
                            sessionTicketCategoriesTtlJitter()
                    );
                    return ticketCategories;
                },
                () -> getTicketCategoriesFromCacheAfterRebuild(cacheKey)
        );
    }

    private List<SessionVO> loadSessionsFromDatabase(Long showId) {
        List<SessionVO> sessions = showMapper.selectSessionsByShowId(showId);
        for (SessionVO session : sessions) {
            List<TicketCategoryVO> ticketCategories = loadTicketCategoriesFromDatabase(session.getId());
            session.setTicketCategories(ticketCategories);
            cacheService.set(
                    RedisKeyConstant.sessionTicketCategoriesKey(session.getId()),
                    ticketCategories,
                    sessionTicketCategoriesTtl(),
                    sessionTicketCategoriesTtlJitter()
            );
        }
        return sessions;
    }

    private List<TicketCategoryVO> loadTicketCategoriesFromDatabase(Long sessionId) {
        return showMapper.selectTicketCategoriesBySessionId(sessionId);
    }

    private <T> T rebuildWithMutex(String cacheKey, Supplier<T> lockedLoader, Supplier<T> cacheWaiter) {
        String lockKey = RedisKeyConstant.cacheLockKey(cacheKey);
        String lockToken = UUID.randomUUID().toString();
        boolean locked = cacheService.tryLock(lockKey, lockToken, lockTtl());
        if (!locked) {
            return cacheWaiter.get();
        }
        try {
            return lockedLoader.get();
        } finally {
            cacheService.releaseLock(lockKey, lockToken);
        }
    }

    private ShowDetailVO getShowDetailFromCacheAfterRebuild(String cacheKey) {
        Object cached = waitForRebuiltCache(cacheKey);
        if (cacheService.isNullValue(cached)) {
            throw new BusinessException("演出不存在");
        }
        if (cached != null) {
            return (ShowDetailVO) cached;
        }
        throw new BusinessException("演出详情缓存重建中，请稍后重试");
    }

    private List<SessionVO> getSessionsFromCacheAfterRebuild(String cacheKey) {
        Object cached = waitForRebuiltCache(cacheKey);
        if (cacheService.isNullValue(cached)) {
            throw new BusinessException("演出不存在");
        }
        if (cached != null) {
            return (List<SessionVO>) cached;
        }
        throw new BusinessException("演出场次缓存重建中，请稍后重试");
    }

    private List<TicketCategoryVO> getTicketCategoriesFromCacheAfterRebuild(String cacheKey) {
        Object cached = waitForRebuiltCache(cacheKey);
        if (cached != null) {
            return (List<TicketCategoryVO>) cached;
        }
        throw new BusinessException("票档缓存重建中，请稍后重试");
    }

    private Object waitForRebuiltCache(String cacheKey) {
        for (int i = 0; i < showCacheProperties.getLockRetryTimes(); i++) {
            sleepBeforeRetry();
            Object cached = cacheService.getRaw(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(showCacheProperties.getLockRetrySleepMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("缓存重建等待被中断");
        }
    }

    private Duration showDetailTtl() {
        return Duration.ofSeconds(showCacheProperties.getShowDetailTtlSeconds());
    }

    private Duration showDetailTtlJitter() {
        return Duration.ofSeconds(showCacheProperties.getShowDetailTtlJitterSeconds());
    }

    private Duration showSessionsTtl() {
        return Duration.ofSeconds(showCacheProperties.getShowSessionsTtlSeconds());
    }

    private Duration showSessionsTtlJitter() {
        return Duration.ofSeconds(showCacheProperties.getShowSessionsTtlJitterSeconds());
    }

    private Duration sessionTicketCategoriesTtl() {
        return Duration.ofSeconds(showCacheProperties.getSessionTicketCategoriesTtlSeconds());
    }

    private Duration sessionTicketCategoriesTtlJitter() {
        return Duration.ofSeconds(showCacheProperties.getSessionTicketCategoriesTtlJitterSeconds());
    }

    private Duration nullTtl() {
        return Duration.ofSeconds(showCacheProperties.getNullTtlSeconds());
    }

    private Duration nullTtlJitter() {
        return Duration.ofSeconds(showCacheProperties.getNullTtlJitterSeconds());
    }

    private Duration lockTtl() {
        return Duration.ofSeconds(showCacheProperties.getLockTtlSeconds());
    }
}
