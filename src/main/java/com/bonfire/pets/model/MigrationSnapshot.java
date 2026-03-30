package com.bonfire.pets.model;

import java.util.List;

public record MigrationSnapshot(
        List<PetDefinition> petDefinitions,
        List<CategoryDefinition> categoryDefinitions,
        List<PlayerPetProfile> playerProfiles,
        List<LegacyAssetBlob> assets
) {

    public MigrationSnapshot {
        petDefinitions = petDefinitions == null ? List.of() : List.copyOf(petDefinitions);
        categoryDefinitions = categoryDefinitions == null ? List.of() : List.copyOf(categoryDefinitions);
        playerProfiles = playerProfiles == null ? List.of() : List.copyOf(playerProfiles);
        assets = assets == null ? List.of() : List.copyOf(assets);
    }
}
