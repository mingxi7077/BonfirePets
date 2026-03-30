package com.bonfire.pets.model;

public record LegacyAssetBlob(
        String assetKey,
        String sourcePath,
        String rawContent,
        String contentHash
) {
}
