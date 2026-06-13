package com.zewbby.smartticket.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSubmitMetadataPrewarmServiceTest {

    @Test
    void prewarmOrderSubmitMetadataRefreshesRelationAndSnapshotCaches() {
        ShowRelationCacheService showRelationCacheService = mock(ShowRelationCacheService.class);
        OrderSnapshotCacheService orderSnapshotCacheService = mock(OrderSnapshotCacheService.class);
        when(showRelationCacheService.relationCount()).thenReturn(3L);
        when(showRelationCacheService.snapshotVersion()).thenReturn(2L);
        when(showRelationCacheService.isLastRefreshSuccessful()).thenReturn(true);
        when(orderSnapshotCacheService.snapshotCount()).thenReturn(3L);
        when(orderSnapshotCacheService.snapshotVersion()).thenReturn(4L);
        when(orderSnapshotCacheService.isLastRefreshSuccessful()).thenReturn(true);
        OrderSubmitMetadataPrewarmService service = new OrderSubmitMetadataPrewarmService(
                showRelationCacheService,
                orderSnapshotCacheService
        );

        var result = service.prewarmOrderSubmitMetadata();

        assertThat(result.getRelationCount()).isEqualTo(3L);
        assertThat(result.getRelationVersion()).isEqualTo(2L);
        assertThat(result.getSnapshotCount()).isEqualTo(3L);
        assertThat(result.getSnapshotVersion()).isEqualTo(4L);
        assertThat(result.isRelationRefreshSuccessful()).isTrue();
        assertThat(result.isSnapshotRefreshSuccessful()).isTrue();
        assertThat(result.getPrewarmedAt()).isNotNull();
        verify(showRelationCacheService).refreshPublishedRelations();
        verify(orderSnapshotCacheService).refreshPublishedSnapshots();
    }
}
