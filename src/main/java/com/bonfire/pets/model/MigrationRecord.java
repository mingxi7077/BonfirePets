package com.bonfire.pets.model;

import java.time.Instant;

public record MigrationRecord(
        String recordId,
        Instant createdAt,
        Instant updatedAt,
        String type,
        String status,
        String operatorName,
        String summary,
        Long rollbackSnapshotId,
        String exportPath,
        String reportPath,
        String sourceHash
) {
}
