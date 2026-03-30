package com.bonfire.pets.model;

public record LegacyImportResult(
        MigrationRecord migrationRecord,
        int petCount,
        int categoryCount,
        int playerCount,
        int assetCount,
        int errorCount,
        int warningCount
) {
}
