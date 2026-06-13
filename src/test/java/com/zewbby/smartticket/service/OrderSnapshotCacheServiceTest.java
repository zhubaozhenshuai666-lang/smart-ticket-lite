package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.OrderSnapshotCacheProperties;
import com.zewbby.smartticket.domain.dto.OrderSnapshotRecord;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSnapshotCacheServiceTest {

    @Mock
    private TicketCategoryMapper ticketCategoryMapper;

    private OrderSnapshotCacheProperties properties;

    private OrderSnapshotCacheService orderSnapshotCacheService;

    @BeforeEach
    void setUp() {
        properties = new OrderSnapshotCacheProperties();
        orderSnapshotCacheService = new OrderSnapshotCacheService(ticketCategoryMapper, properties);
    }

    @Test
    void refreshBuildsSnapshotMapAndReadsFromMemory() {
        when(ticketCategoryMapper.selectPublishedOrderSnapshots()).thenReturn(List.of(snapshotRecord()));

        orderSnapshotCacheService.refreshPublishedSnapshots();

        var snapshot = orderSnapshotCacheService.getPublishedSnapshot(1L, 10L, 100L);

        assertThat(orderSnapshotCacheService.snapshotCount()).isEqualTo(1);
        assertThat(orderSnapshotCacheService.snapshotVersion()).isEqualTo(1);
        assertThat(snapshot.getShowTitle()).isEqualTo("测试演唱会");
        assertThat(snapshot.getTicketPrice()).isEqualByComparingTo("880.00");
        verify(ticketCategoryMapper, never()).selectOrderSnapshot(1L, 10L, 100L);
    }

    @Test
    void cacheFailsClosedWhenSnapshotIsNotReady() {
        assertThatThrownBy(() -> orderSnapshotCacheService.getPublishedSnapshot(1L, 10L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单快照缓存未就绪");
    }

    @Test
    void disabledCacheFallsBackToDatabase() {
        properties.setEnabled(false);
        when(ticketCategoryMapper.selectOrderSnapshot(1L, 10L, 100L)).thenReturn(new com.zewbby.smartticket.domain.dto.OrderSnapshot());

        assertThat(orderSnapshotCacheService.getPublishedSnapshot(1L, 10L, 100L)).isNotNull();

        verify(ticketCategoryMapper).selectOrderSnapshot(1L, 10L, 100L);
    }

    @Test
    void refreshFailureKeepsOldSnapshotAvailable() {
        when(ticketCategoryMapper.selectPublishedOrderSnapshots())
                .thenReturn(List.of(snapshotRecord()))
                .thenThrow(new RuntimeException("db down"));

        orderSnapshotCacheService.refreshPublishedSnapshots();
        orderSnapshotCacheService.refreshPublishedSnapshots();

        assertThat(orderSnapshotCacheService.getPublishedSnapshot(1L, 10L, 100L)).isNotNull();
        assertThat(orderSnapshotCacheService.isLastRefreshSuccessful()).isFalse();
        assertThat(orderSnapshotCacheService.snapshotCount()).isEqualTo(1);
    }

    private OrderSnapshotRecord snapshotRecord() {
        return new OrderSnapshotRecord(
                1L,
                10L,
                100L,
                "测试演唱会",
                LocalDateTime.of(2026, 6, 20, 19, 30),
                "内场票",
                new BigDecimal("880.00")
        );
    }
}
