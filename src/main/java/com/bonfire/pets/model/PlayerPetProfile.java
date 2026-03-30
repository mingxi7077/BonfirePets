package com.bonfire.pets.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record PlayerPetProfile(
        UUID playerUuid,
        String playerName,
        Set<String> ownedPetIds,
        String activePetId,
        Map<String, String> petStatsByPetId,
        Map<String, String> customNamesByPetId,
        Map<String, String> inventoriesByPetId,
        List<String> rawPetStats,
        String legacySourceType,
        String sourcePath,
        String rawYaml,
        String contentHash
) {

    public PlayerPetProfile {
        ownedPetIds = ownedPetIds == null ? Set.of() : Set.copyOf(ownedPetIds);
        petStatsByPetId = petStatsByPetId == null ? Map.of() : Map.copyOf(petStatsByPetId);
        customNamesByPetId = customNamesByPetId == null ? Map.of() : Map.copyOf(customNamesByPetId);
        inventoriesByPetId = inventoriesByPetId == null ? Map.of() : Map.copyOf(inventoriesByPetId);
        rawPetStats = rawPetStats == null ? List.of() : List.copyOf(rawPetStats);
    }
}
