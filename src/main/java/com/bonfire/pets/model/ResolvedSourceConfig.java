package com.bonfire.pets.model;

public record ResolvedSourceConfig(
        String mode,
        String host,
        int port,
        String database,
        String user,
        String password,
        String tableName,
        int queryTimeoutSeconds,
        String description
) {
}

