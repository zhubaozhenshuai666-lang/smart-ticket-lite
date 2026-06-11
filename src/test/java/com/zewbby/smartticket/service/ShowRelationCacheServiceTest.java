package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.ShowRelationCacheProperties;
import com.zewbby.smartticket.domain.dto.ShowRelationRecord;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowRelationCacheServiceTest {

    @Mock
    private TicketCategoryMapper ticketCategoryMapper;

    private ShowRelationCacheProperties properties;

    private ShowRelationCacheService showRelationCacheService;

    @BeforeEach
    void setUp() {
        properties = new ShowRelationCacheProperties();
        showRelationCacheService = new ShowRelationCacheService(ticketCategoryMapper, properties);
    }

    @Test
    void refreshBuildsImmutableSnapshotAndValidatesFromMemory() {
        when(ticketCategoryMapper.selectPublishedShowRelations()).thenReturn(List.of(
                new ShowRelationRecord(1L, 10L, 100L),
                new ShowRelationRecord(1L, 10L, 101L),
                new ShowRelationRecord(2L, 20L, 200L)
        ));

        showRelationCacheService.refreshPublishedRelations();

        assertThat(showRelationCacheService.relationCount()).isEqualTo(3);
        assertThat(showRelationCacheService.snapshotVersion()).isEqualTo(1);
        assertThat(showRelationCacheService.existsPublishedRelation(1L, 10L, 100L)).isTrue();
        assertThat(showRelationCacheService.existsPublishedRelation(1L, 10L, 999L)).isFalse();
        verify(ticketCategoryMapper, never()).existsShowSessionTicketCategoryRelation(1L, 10L, 100L);
    }

    @Test
    void cacheFailsClosedWhenSnapshotIsNotReady() {
        assertThatThrownBy(() -> showRelationCacheService.existsPublishedRelation(1L, 10L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演出关系缓存未就绪");
    }

    @Test
    void disabledCacheFallsBackToDatabaseCheck() {
        properties.setEnabled(false);
        when(ticketCategoryMapper.existsShowSessionTicketCategoryRelation(1L, 10L, 100L)).thenReturn(true);

        assertThat(showRelationCacheService.existsPublishedRelation(1L, 10L, 100L)).isTrue();

        verify(ticketCategoryMapper).existsShowSessionTicketCategoryRelation(1L, 10L, 100L);
    }

    @Test
    void refreshFailureKeepsOldSnapshotAvailable() {
        when(ticketCategoryMapper.selectPublishedShowRelations())
                .thenReturn(List.of(new ShowRelationRecord(1L, 10L, 100L)))
                .thenThrow(new RuntimeException("db down"));

        showRelationCacheService.refreshPublishedRelations();
        showRelationCacheService.refreshPublishedRelations();

        assertThat(showRelationCacheService.existsPublishedRelation(1L, 10L, 100L)).isTrue();
        assertThat(showRelationCacheService.isLastRefreshSuccessful()).isFalse();
        assertThat(showRelationCacheService.relationCount()).isEqualTo(1);
    }
}
