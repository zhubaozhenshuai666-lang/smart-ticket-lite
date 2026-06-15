package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.ShowCacheProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;
import com.zewbby.smartticket.mapper.ShowMapper;
import com.zewbby.smartticket.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowServiceImplTest {

    @Mock
    private ShowMapper showMapper;

    @Mock
    private CacheService cacheService;

    private ShowCacheProperties properties;

    private ShowServiceImpl showService;

    @BeforeEach
    void setUp() {
        properties = new ShowCacheProperties();
        properties.setLockRetrySleepMillis(1L);
        showService = new ShowServiceImpl(showMapper, cacheService, properties);
    }

    @Test
    void cachedNullShowDetailRejectsWithoutQueryingMysql() {
        String cacheKey = RedisKeyConstant.showDetailKey(404L);
        when(cacheService.getRaw(cacheKey)).thenReturn(CacheService.NULL_VALUE);
        when(cacheService.isNullValue(CacheService.NULL_VALUE)).thenReturn(true);

        assertThatThrownBy(() -> showService.getShowDetail(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("演出不存在");

        verifyNoInteractions(showMapper);
    }

    @Test
    void missingShowDetailCachesNullValue() {
        String cacheKey = RedisKeyConstant.showDetailKey(404L);
        String lockKey = RedisKeyConstant.cacheLockKey(cacheKey);
        when(cacheService.getRaw(cacheKey)).thenReturn(null, null);
        when(cacheService.tryLock(eq(lockKey), anyString(), eq(Duration.ofSeconds(5)))).thenReturn(true);
        when(showMapper.selectShowDetailById(404L)).thenReturn(null);

        assertThatThrownBy(() -> showService.getShowDetail(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("演出不存在");

        verify(cacheService).setNullValue(
                eq(cacheKey),
                eq(Duration.ofSeconds(60)),
                eq(Duration.ofSeconds(30))
        );
        verify(cacheService).releaseLock(eq(lockKey), anyString());
    }

    @Test
    void showDetailMissLoadsOnceAndCachesRelatedDataWhenLockAcquired() {
        String detailKey = RedisKeyConstant.showDetailKey(1L);
        String sessionsKey = RedisKeyConstant.showSessionsKey(1L);
        String categoriesKey = RedisKeyConstant.sessionTicketCategoriesKey(10L);
        String lockKey = RedisKeyConstant.cacheLockKey(detailKey);
        ShowDetailVO detail = new ShowDetailVO();
        detail.setId(1L);
        detail.setTitle("演出");
        SessionVO session = new SessionVO();
        session.setId(10L);
        TicketCategoryVO category = new TicketCategoryVO();
        category.setId(100L);
        category.setPrice(BigDecimal.TEN);
        List<SessionVO> sessions = List.of(session);
        List<TicketCategoryVO> categories = List.of(category);

        when(cacheService.getRaw(detailKey)).thenReturn(null, null);
        when(cacheService.tryLock(eq(lockKey), anyString(), eq(Duration.ofSeconds(5)))).thenReturn(true);
        when(showMapper.selectShowDetailById(1L)).thenReturn(detail);
        when(showMapper.selectSessionsByShowId(1L)).thenReturn(sessions);
        when(showMapper.selectTicketCategoriesBySessionId(10L)).thenReturn(categories);

        ShowDetailVO result = showService.getShowDetail(1L);

        assertThat(result.getSessions()).containsExactly(session);
        assertThat(result.getSessions().get(0).getTicketCategories()).containsExactly(category);
        verify(cacheService).set(
                eq(categoriesKey),
                eq(categories),
                eq(Duration.ofSeconds(600)),
                eq(Duration.ofSeconds(120))
        );
        verify(cacheService).set(
                eq(sessionsKey),
                eq(sessions),
                eq(Duration.ofSeconds(600)),
                eq(Duration.ofSeconds(120))
        );
        verify(cacheService).set(
                eq(detailKey),
                eq(detail),
                eq(Duration.ofSeconds(1800)),
                eq(Duration.ofSeconds(300))
        );
        verify(cacheService).releaseLock(eq(lockKey), anyString());
    }

    @Test
    void lockBusyWaitsForRebuiltCacheAndDoesNotQueryMysql() {
        properties.setLockRetryTimes(1);
        String cacheKey = RedisKeyConstant.showDetailKey(1L);
        String lockKey = RedisKeyConstant.cacheLockKey(cacheKey);
        ShowDetailVO cached = new ShowDetailVO();
        cached.setId(1L);
        when(cacheService.getRaw(cacheKey)).thenReturn(null, cached);
        when(cacheService.tryLock(eq(lockKey), anyString(), eq(Duration.ofSeconds(5)))).thenReturn(false);

        ShowDetailVO result = showService.getShowDetail(1L);

        assertThat(result).isSameAs(cached);
        verify(showMapper, never()).selectShowDetailById(1L);
    }
}
