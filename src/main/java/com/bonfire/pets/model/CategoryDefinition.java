package com.bonfire.pets.model;

import java.util.List;

public record CategoryDefinition(
        String categoryId,
        String legacyId,
        String displayName,
        boolean defaultCategory,
        List<String> petIds,
        List<String> excludedCategoryIds,
        String iconName,
        String iconYaml,
        String sourcePath,
        String contentHash
) {

    public CategoryDefinition {
        petIds = petIds == null ? List.of() : List.copyOf(petIds);
        excludedCategoryIds = excludedCategoryIds == null ? List.of() : List.copyOf(excludedCategoryIds);
    }
}
