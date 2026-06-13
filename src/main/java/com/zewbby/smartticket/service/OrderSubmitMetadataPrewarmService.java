package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.vo.MetadataPrewarmResultVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderSubmitMetadataPrewarmService {

    private final ShowRelationCacheService showRelationCacheService;

    private final OrderSnapshotCacheService orderSnapshotCacheService;

    public OrderSubmitMetadataPrewarmService(ShowRelationCacheService showRelationCacheService,
                                             OrderSnapshotCacheService orderSnapshotCacheService) {
        this.showRelationCacheService = showRelationCacheService;
        this.orderSnapshotCacheService = orderSnapshotCacheService;
    }

    public MetadataPrewarmResultVO prewarmOrderSubmitMetadata() {
        showRelationCacheService.refreshPublishedRelations();
        orderSnapshotCacheService.refreshPublishedSnapshots();
        return new MetadataPrewarmResultVO(
                showRelationCacheService.relationCount(),
                showRelationCacheService.snapshotVersion(),
                showRelationCacheService.isLastRefreshSuccessful(),
                orderSnapshotCacheService.snapshotCount(),
                orderSnapshotCacheService.snapshotVersion(),
                orderSnapshotCacheService.isLastRefreshSuccessful(),
                LocalDateTime.now()
        );
    }
}
