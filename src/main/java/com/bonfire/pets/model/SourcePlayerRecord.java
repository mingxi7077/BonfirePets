package com.bonfire.pets.model;

import com.bonfire.pets.util.HashUtil;

import java.util.UUID;

public record SourcePlayerRecord(
        UUID playerUuid,
        String playerName,
        String namesRaw,
        String inventoriesRaw,
        String dataRaw
) {

    public SourcePlayerRecord {
        playerName = playerName == null ? "" : playerName;
        namesRaw = namesRaw == null ? "" : namesRaw;
        inventoriesRaw = inventoriesRaw == null ? "" : inventoriesRaw;
        dataRaw = dataRaw == null ? "" : dataRaw;
    }

    public String contentHash() {
        return HashUtil.sha256(namesRaw + "\u0000" + inventoriesRaw + "\u0000" + dataRaw);
    }
}

