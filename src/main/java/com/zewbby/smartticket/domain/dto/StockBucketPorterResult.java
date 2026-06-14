package com.zewbby.smartticket.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockBucketPorterResult {

    private boolean lockAcquired;

    private boolean completed;

    private Long ticketCategoryId;

    private Integer fromVersion;

    private Integer toVersion;

    private Integer movedBucketCount;

    private Integer movedQuantity;

    private String message;

    public static StockBucketPorterResult lockSkipped(Long ticketCategoryId,
                                                      Integer fromVersion,
                                                      Integer toVersion) {
        return new StockBucketPorterResult(
                false,
                false,
                ticketCategoryId,
                fromVersion,
                toVersion,
                0,
                0,
                "Porter lock is held by another worker"
        );
    }

    public static StockBucketPorterResult completed(Long ticketCategoryId,
                                                    Integer fromVersion,
                                                    Integer toVersion,
                                                    Integer movedBucketCount,
                                                    Integer movedQuantity) {
        return new StockBucketPorterResult(
                true,
                true,
                ticketCategoryId,
                fromVersion,
                toVersion,
                movedBucketCount,
                movedQuantity,
                "Porter move completed"
        );
    }
}
