package com.bonfire.pets.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public record BonfirePetsConfig(
        boolean enabled,
        String adminPermission,
        StorageSettings storage,
        PathSettings paths,
        LegacySettings legacy,
        BetterModelSettings betterModel,
        ValidationSettings validation
) {

    public static BonfirePetsConfig from(JavaPlugin plugin, FileConfiguration config) {
        return new BonfirePetsConfig(
                config.getBoolean("enabled", true),
                config.getString("admin-permission", "bonfire.pets.admin"),
                new StorageSettings(config.getString("storage.sqlite-path", "database/bonfirepets.db")),
                new PathSettings(
                        config.getString("paths.legacy-plugins-dir", plugin.getDataFolder().toPath().resolve("../legacy/plugins").normalize().toString()),
                        config.getString("paths.target-plugins-dir", plugin.getDataFolder().toPath().resolve("../target/plugins").normalize().toString()),
                        config.getString("paths.migration-workspace-dir", plugin.getDataFolder().toPath().resolve("../migration-input").normalize().toString()),
                        config.getString("paths.reports-dir", "reports"),
                        config.getString("paths.exports-dir", "exports")
                ),
                new LegacySettings(
                        config.getString("legacy.mcpets-dir", "MCPets"),
                        config.getString("legacy.mythicmobs-dir", "MythicMobs"),
                        config.getString("legacy.itemsadder-dir", "ItemsAdder"),
                        config.getString("legacy.mcpets-config-file", "MCPets/config.yml"),
                        config.getString("legacy.menu-icons-file", "MCPets/menuIcons.yml"),
                        config.getString("legacy.pet-foods-file", "MCPets/petfoods.yml"),
                        config.getString("legacy.player-data-dir", "MCPets/PlayerData"),
                        config.getBoolean("legacy.import-mysql-when-available", true),
                        Math.max(3, config.getInt("legacy.mysql-connect-timeout-seconds", 8))
                ),
                new BetterModelSettings(
                        config.getBoolean("bettermodel.register-event-hooks", true),
                        config.getBoolean("bettermodel.require-plugin-for-import-validation", false)
                ),
                new ValidationSettings(
                        config.getBoolean("validation.write-report-file", true),
                        Math.max(5, config.getInt("validation.sample-issue-limit", 20)),
                        config.getBoolean("validation.strict-model-check", false),
                        config.getBoolean("validation.strict-mythicmobs-check", true)
                )
        );
    }

    public Path resolveSqlitePath(JavaPlugin plugin) {
        return resolveAgainst(plugin.getDataFolder().toPath(), storage.sqlitePath());
    }

    public Path resolveReportsDir(JavaPlugin plugin) {
        return resolveAgainst(plugin.getDataFolder().toPath(), paths.reportsDir());
    }

    public Path resolveExportsDir(JavaPlugin plugin) {
        return resolveAgainst(plugin.getDataFolder().toPath(), paths.exportsDir());
    }

    public Path resolveLegacyPluginsDir() {
        return Path.of(paths.legacyPluginsDir()).normalize();
    }

    public Path resolveTargetPluginsDir() {
        return Path.of(paths.targetPluginsDir()).normalize();
    }

    public Path resolveMigrationWorkspaceDir() {
        return Path.of(paths.migrationWorkspaceDir()).normalize();
    }

    public Path resolveLegacyMcpetsDir() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.mcpetsDir());
    }

    public Path resolveLegacyMythicMobsDir() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.mythicmobsDir());
    }

    public Path resolveLegacyItemsAdderDir() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.itemsadderDir());
    }

    public Path resolveLegacyMcpetsConfig() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.mcpetsConfigFile());
    }

    public Path resolveLegacyMenuIcons() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.menuIconsFile());
    }

    public Path resolveLegacyPetFoods() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.petFoodsFile());
    }

    public Path resolveLegacyPlayerDataDir() {
        return resolveAgainst(resolveLegacyPluginsDir(), legacy.playerDataDir());
    }

    private static Path resolveAgainst(Path base, String configured) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return base.resolve(path).normalize();
    }

    public record StorageSettings(String sqlitePath) {
    }

    public record PathSettings(
            String legacyPluginsDir,
            String targetPluginsDir,
            String migrationWorkspaceDir,
            String reportsDir,
            String exportsDir
    ) {
    }

    public record LegacySettings(
            String mcpetsDir,
            String mythicmobsDir,
            String itemsadderDir,
            String mcpetsConfigFile,
            String menuIconsFile,
            String petFoodsFile,
            String playerDataDir,
            boolean importMysqlWhenAvailable,
            int mysqlConnectTimeoutSeconds
    ) {
    }

    public record BetterModelSettings(
            boolean registerEventHooks,
            boolean requirePluginForImportValidation
    ) {
    }

    public record ValidationSettings(
            boolean writeReportFile,
            int sampleIssueLimit,
            boolean strictModelCheck,
            boolean strictMythicMobsCheck
    ) {
    }
}
