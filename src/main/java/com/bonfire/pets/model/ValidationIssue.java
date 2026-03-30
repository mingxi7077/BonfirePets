package com.bonfire.pets.model;

public record ValidationIssue(
        Severity severity,
        String code,
        String message,
        String reference
) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
