package com.bonfire.pets.model;

import java.time.Instant;
import java.util.UUID;

public record RollbackJobRecord(
        String jobUuid,
        UUID playerUuid,
        String playerName,
        long targetSnapshotId,
        Long preSnapshotId,
        String operatorName,
        String status,
        String message,
        Instant createdAt,
        Instant updatedAt
) {
}

