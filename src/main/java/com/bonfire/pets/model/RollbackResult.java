package com.bonfire.pets.model;

public record RollbackResult(
        MigrationRecord migrationRecord,
        long snapshotId,
        int petCount,
        int categoryCount,
        int playerCount,
        String message
) {
}
