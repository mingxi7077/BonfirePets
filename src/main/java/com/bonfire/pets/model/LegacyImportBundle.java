package com.bonfire.pets.model;

import java.util.List;

public record LegacyImportBundle(
        List<PetDefinition> petDefinitions,
        List<CategoryDefinition> categoryDefinitions,
        List<PlayerPetProfile> playerProfiles,
        List<LegacyAssetBlob> assets,
        MythicMobsScanSummary mythicMobsScanSummary,
        List<ValidationIssue> issues,
        String sourceHash
) {

    public LegacyImportBundle {
        petDefinitions = petDefinitions == null ? List.of() : List.copyOf(petDefinitions);
        categoryDefinitions = categoryDefinitions == null ? List.of() : List.copyOf(categoryDefinitions);
        playerProfiles = playerProfiles == null ? List.of() : List.copyOf(playerProfiles);
        assets = assets == null ? List.of() : List.copyOf(assets);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
