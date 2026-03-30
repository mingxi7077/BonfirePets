package com.bonfire.pets.model;

import java.time.Instant;
import java.util.List;

public record SourceCompatibilityReport(
        boolean schemaCompatible,
        boolean readOnly,
        String reason,
        String tableName,
        List<String> tableColumns,
        List<String> missingRequiredColumns,
        Instant checkedAt
) {

    public SourceCompatibilityReport {
        reason = reason == null ? "" : reason;
        tableName = tableName == null ? "" : tableName;
        tableColumns = tableColumns == null ? List.of() : List.copyOf(tableColumns);
        missingRequiredColumns = missingRequiredColumns == null ? List.of() : List.copyOf(missingRequiredColumns);
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }

    public static SourceCompatibilityReport compatible(String tableName, List<String> tableColumns, Instant checkedAt) {
        return new SourceCompatibilityReport(true, false, "ok", tableName, tableColumns, List.of(), checkedAt);
    }

    public static SourceCompatibilityReport readOnly(String tableName, String reason, List<String> tableColumns, List<String> missingRequiredColumns, Instant checkedAt) {
        return new SourceCompatibilityReport(false, true, reason, tableName, tableColumns, missingRequiredColumns, checkedAt);
    }

    public static SourceCompatibilityReport unknown(String reason) {
        return readOnly("", reason, List.of(), List.of(), Instant.now());
    }
}
