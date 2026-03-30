package com.bonfire.pets.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public record BridgeConfig(
        boolean enabled,
        String adminPermission,
        StatusSettings status,
        StorageSettings storage,
        BackupSettings backup,
        RollbackSettings rollback,
        SourceSettings source
) {

    public static BridgeConfig from(FileConfiguration config) {
        return new BridgeConfig(
                config.getBoolean("enabled", true),
                config.getString("admin-permission", "bonfire.pets.admin"),
                new StatusSettings(config.getBoolean("status.show-softdepends", true)),
                new StorageSettings(config.getString("storage.sqlite-path", "backup.db")),
                new BackupSettings(
                        Math.max(1, config.getInt("backup.interval-minutes", 60)),
                        Math.max(1, config.getInt("backup.retention-days", 7)),
                        config.getBoolean("backup.only-when-changed", true),
                        config.getBoolean("backup.include-online-players-only", false)
                ),
                new RollbackSettings(
                        config.getBoolean("rollback.require-player-offline", true),
                        config.getBoolean("rollback.create-pre-rollback-snapshot", true),
                        config.getBoolean("rollback.verify-after-apply", true),
                        config.getBoolean("rollback.require-confirmation", true),
                        Math.max(30, config.getInt("rollback.confirmation-ttl-seconds", 180))
                ),
                new SourceSettings(
                        config.getString("source.mode", "auto"),
                        config.getString("source.mcpets-config-path", "../MCPets/config.yml"),
                        config.getString("source.host", "127.0.0.1"),
                        Math.max(1, config.getInt("source.port", 3306)),
                        config.getString("source.database", "mcpets_db"),
                        config.getString("source.user", "root"),
                        config.getString("source.password", "change-me"),
                        config.getString("source.table-prefix", ""),
                        config.getString("source.table-name", ""),
                        Math.max(1, config.getInt("source.query-timeout-seconds", 15))
                )
        );
    }

    public Path resolveSqlitePath(JavaPlugin plugin) {
        Path configured = Path.of(storage.sqlitePath());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return plugin.getDataFolder().toPath().resolve(configured).normalize();
    }

    public Path resolveMcpetsConfigPath(JavaPlugin plugin) {
        Path configured = Path.of(source.mcpetsConfigPath());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return plugin.getDataFolder().toPath().resolve(configured).normalize();
    }

    public record StorageSettings(String sqlitePath) {
    }

    public record StatusSettings(boolean showSoftdepends) {
    }

    public record BackupSettings(
            int intervalMinutes,
            int retentionDays,
            boolean onlyWhenChanged,
            boolean includeOnlinePlayersOnly
    ) {
    }

    public record RollbackSettings(
            boolean requirePlayerOffline,
            boolean createPreRollbackSnapshot,
            boolean verifyAfterApply,
            boolean requireConfirmation,
            int confirmationTtlSeconds
    ) {
    }

    public record SourceSettings(
            String mode,
            String mcpetsConfigPath,
            String host,
            int port,
            String database,
            String user,
            String password,
            String tablePrefix,
            String tableName,
            int queryTimeoutSeconds
    ) {
    }
}

