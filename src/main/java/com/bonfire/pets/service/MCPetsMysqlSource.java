package com.bonfire.pets.service;

import com.bonfire.pets.model.ResolvedSourceConfig;
import com.bonfire.pets.model.SourceCompatibilityReport;
import com.bonfire.pets.model.SourcePlayerRecord;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class MCPetsMysqlSource implements AutoCloseable {

    private static final List<String> REQUIRED_COLUMNS = List.of("uuid", "names", "inventories", "data");

    private final ResolvedSourceConfig config;

    public MCPetsMysqlSource(ResolvedSourceConfig config) {
        this.config = config;
    }

    public ResolvedSourceConfig config() {
        return config;
    }

    public SourceCompatibilityReport inspectCompatibility() throws SQLException {
        Instant checkedAt = Instant.now();
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        List<String> rawColumns = new ArrayList<>();

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            statement.setString(1, config.database());
            statement.setString(2, config.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rawColumns.add(resultSet.getString(1));
                }
            }
        }

        Set<String> normalizedLookup = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> normalizedColumns = new LinkedHashSet<>();
        for (String rawColumn : rawColumns) {
            if (rawColumn == null || rawColumn.isBlank()) {
                continue;
            }
            String normalized = rawColumn.trim().toLowerCase(Locale.ROOT);
            normalizedLookup.add(normalized);
            normalizedColumns.add(normalized);
        }

        List<String> tableColumns = List.copyOf(normalizedColumns);
        if (tableColumns.isEmpty()) {
            return SourceCompatibilityReport.readOnly(
                    config.tableName(),
                    "Source table was not found or metadata lookup returned no columns",
                    tableColumns,
                    REQUIRED_COLUMNS,
                    checkedAt
            );
        }

        List<String> missingRequiredColumns = REQUIRED_COLUMNS.stream()
                .filter(requiredColumn -> !normalizedLookup.contains(requiredColumn))
                .toList();
        if (!missingRequiredColumns.isEmpty()) {
            return SourceCompatibilityReport.readOnly(
                    config.tableName(),
                    "Required MCPets columns are missing",
                    tableColumns,
                    missingRequiredColumns,
                    checkedAt
            );
        }

        return SourceCompatibilityReport.compatible(config.tableName(), tableColumns, checkedAt);
    }

    public List<SourcePlayerRecord> loadAllPlayers() throws SQLException {
        String sql = "SELECT uuid, names, inventories, data FROM " + config.tableName();
        List<SourcePlayerRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    records.add(mapRow(resultSet));
                }
            }
        }
        return records;
    }

    public Optional<SourcePlayerRecord> loadPlayer(UUID playerUuid) throws SQLException {
        String sql = "SELECT uuid, names, inventories, data FROM " + config.tableName() + " WHERE uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    public long countPlayers() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + config.tableName();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        }
        return 0L;
    }

    public void upsertPlayer(SourcePlayerRecord record) throws SQLException {
        String deleteSql = "DELETE FROM " + config.tableName() + " WHERE uuid = ?";
        String insertSql = "INSERT INTO " + config.tableName() + " (uuid, names, inventories, data) VALUES (?, ?, ?, ?)";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement delete = connection.prepareStatement(deleteSql);
                 PreparedStatement insert = connection.prepareStatement(insertSql)) {
                delete.setQueryTimeout(config.queryTimeoutSeconds());
                delete.setString(1, record.playerUuid().toString());
                delete.executeUpdate();

                insert.setQueryTimeout(config.queryTimeoutSeconds());
                insert.setString(1, record.playerUuid().toString());
                insert.setString(2, record.namesRaw());
                insert.setString(3, record.inventoriesRaw());
                insert.setString(4, record.dataRaw());
                insert.executeUpdate();

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        String url = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC";
        return DriverManager.getConnection(url, config.user(), config.password());
    }

    private SourcePlayerRecord mapRow(ResultSet resultSet) throws SQLException {
        UUID playerUuid = UUID.fromString(resultSet.getString("uuid"));
        String namesRaw = resultSet.getString("names");
        String inventoriesRaw = resultSet.getString("inventories");
        String dataRaw = resultSet.getString("data");
        return new SourcePlayerRecord(playerUuid, "", namesRaw, inventoriesRaw, dataRaw);
    }

    @Override
    public void close() {
    }
}
