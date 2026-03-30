package com.bonfire.pets.model;

import java.time.Instant;
import java.util.UUID;

public record SnapshotRecord(
        long id,
        UUID playerUuid,
        String playerName,
        String sourceType,
        String snapshotType,
        Instant createdAt,
        String namesRaw,
        String inventoriesRaw,
        String dataRaw,
        String contentHash,
        String note
) {

    public SourcePlayerRecord toSourcePlayerRecord() {
        return new SourcePlayerRecord(playerUuid, playerName, namesRaw, inventoriesRaw, dataRaw);
    }
}

