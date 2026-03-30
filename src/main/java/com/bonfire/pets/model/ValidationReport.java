package com.bonfire.pets.model;

import java.time.Instant;
import java.util.List;

public record ValidationReport(
        Instant createdAt,
        int petCount,
        int categoryCount,
        int playerCount,
        BetterModelProbe betterModelProbe,
        MythicMobsScanSummary mythicMobsScanSummary,
        List<ValidationIssue> issues,
        String reportPath
) {

    public ValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public long errorCount() {
        return issues.stream().filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR).count();
    }

    public long warningCount() {
        return issues.stream().filter(issue -> issue.severity() == ValidationIssue.Severity.WARNING).count();
    }
}
