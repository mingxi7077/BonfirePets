package com.bonfire.pets.model;

import java.util.UUID;

public record GiveResult(
        UUID playerUuid,
        String playerName,
        String petId,
        boolean activeAssigned,
        int ownedPetCount,
        String message
) {
}
