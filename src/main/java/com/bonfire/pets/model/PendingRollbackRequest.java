package com.bonfire.pets.model;

import java.time.Instant;

public record PendingRollbackRequest(
        String token,
        PlayerReference player,
        long snapshotId,
        String operatorName,
        Instant expiresAt,
        String previewMessage
) {

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
