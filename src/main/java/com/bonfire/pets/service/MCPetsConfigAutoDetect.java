package com.bonfire.pets.service;

import com.bonfire.pets.config.BridgeConfig;
import com.bonfire.pets.model.ResolvedSourceConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MCPetsConfigAutoDetect {

    public ResolvedSourceConfig resolve(JavaPlugin plugin, BridgeConfig.SourceSettings settings, Path configPath) {
        if (!"auto".equalsIgnoreCase(settings.mode())) {
            return buildManual(settings);
        }

        if (!Files.exists(configPath)) {
            throw new IllegalStateException("MCPets config not found: " + configPath);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configPath.toFile());
        boolean disableMysql = yaml.getBoolean("DisableMySQL", false);
        if (disableMysql) {
            throw new IllegalStateException("MCPets DisableMySQL=true. This bridge only supports MySQL source in v0.1.");
        }

        String host = valueOrDefault(yaml.getString("MySQL.Host"), settings.host());
        int port = parsePort(valueOrDefault(yaml.getString("MySQL.Port"), Integer.toString(settings.port())));
        String database = valueOrDefault(yaml.getString("MySQL.Database"), settings.database());
        String user = valueOrDefault(yaml.getString("MySQL.User"), settings.user());
        String password = valueOrDefault(yaml.getString("MySQL.Password"), settings.password());
        String tablePrefix = valueOrDefault(yaml.getString("MySQL.Prefix"), settings.tablePrefix());
        String explicitTableName = valueOrDefault(settings.tableName(), "");
        String tableName = explicitTableName.isBlank() ? tablePrefix + "mcpets_player_data" : explicitTableName;
        String description = "auto mysql " + host + ":" + port + "/" + database + " table=" + tableName;

        return new ResolvedSourceConfig(
                "auto",
                host,
                port,
                database,
                user,
                password,
                tableName,
                settings.queryTimeoutSeconds(),
                description
        );
    }

    private ResolvedSourceConfig buildManual(BridgeConfig.SourceSettings settings) {
        String explicitTableName = valueOrDefault(settings.tableName(), "");
        String tableName = explicitTableName.isBlank()
                ? valueOrDefault(settings.tablePrefix(), "") + "mcpets_player_data"
                : explicitTableName;
        String description = "manual mysql " + settings.host() + ":" + settings.port() + "/" + settings.database() + " table=" + tableName;

        return new ResolvedSourceConfig(
                "manual",
                settings.host(),
                settings.port(),
                settings.database(),
                settings.user(),
                settings.password(),
                tableName,
                settings.queryTimeoutSeconds(),
                description
        );
    }

    private String valueOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid MCPets MySQL port: " + value, exception);
        }
    }
}

