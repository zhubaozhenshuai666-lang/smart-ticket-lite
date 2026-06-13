package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetadataPrewarmResultVO {

    private long relationCount;

    private long relationVersion;

    private boolean relationRefreshSuccessful;

    private long snapshotCount;

    private long snapshotVersion;

    private boolean snapshotRefreshSuccessful;

    private LocalDateTime prewarmedAt;
}
