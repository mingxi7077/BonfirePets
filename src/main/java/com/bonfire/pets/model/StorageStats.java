package com.bonfire.pets.model;

public record StorageStats(
        long snapshotCount,
        long rollbackJobCount,
        long auditLogCount,
        BackupRunSummary lastBackupRun
) {
}
