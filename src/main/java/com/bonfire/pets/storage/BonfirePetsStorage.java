package com.bonfire.pets.storage;

import com.bonfire.pets.model.CategoryDefinition;
import com.bonfire.pets.model.LegacyAssetBlob;
import com.bonfire.pets.model.MigrationRecord;
import com.bonfire.pets.model.MigrationSnapshot;
import com.bonfire.pets.model.PetDefinition;
import com.bonfire.pets.model.PlayerPetProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class BonfirePetsStorage implements AutoCloseable {

    private static final Type PET_TYPE = new TypeToken<PetDefinition>() {
    }.getType();
    private static final Type CATEGORY_TYPE = new TypeToken<CategoryDefinition>() {
    }.getType();
    private static final Type PROFILE_TYPE = new TypeToken<PlayerPetProfile>() {
    }.getType();
    private static final Type ASSET_TYPE = new TypeToken<LegacyAssetBlob>() {
    }.getType();
    private static final Type SNAPSHOT_TYPE = new TypeToken<MigrationSnapshot>() {
    }.getType();

    private final Path sqlitePath;
    private final String jdbcUrl;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public BonfirePetsStorage(Path sqlitePath) {
        this.sqlitePath = sqlitePath;
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();
    }

    public Path sqlitePath() {
        return sqlitePath;
    }

    public synchronized void initialize() throws SQLException {
        try {
            Path parent = sqlitePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new SQLException("Failed to create sqlite directory for " + sqlitePath, exception);
        }

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");

            statement.execute("CREATE TABLE IF NOT EXISTS pet_definitions ("
                    + "pet_id TEXT PRIMARY KEY,"
                    + "legacy_id TEXT NOT NULL,"
                    + "permission_node TEXT,"
                    + "category_ids_json TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL,"
                    + "import_record_id TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS category_definitions ("
                    + "category_id TEXT PRIMARY KEY,"
                    + "payload_json TEXT NOT NULL,"
                    + "import_record_id TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS player_pet_profiles ("
                    + "player_uuid TEXT PRIMARY KEY,"
                    + "player_name TEXT NOT NULL,"
                    + "active_pet_id TEXT,"
                    + "owned_pet_ids_json TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL,"
                    + "import_record_id TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS system_assets ("
                    + "asset_key TEXT PRIMARY KEY,"
                    + "payload_json TEXT NOT NULL,"
                    + "import_record_id TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS migration_records ("
                    + "record_id TEXT PRIMARY KEY,"
                    + "created_at TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "status TEXT NOT NULL,"
                    + "operator_name TEXT NOT NULL,"
                    + "summary TEXT NOT NULL,"
                    + "rollback_snapshot_id INTEGER,"
                    + "export_path TEXT,"
                    + "report_path TEXT,"
                    + "source_hash TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS import_snapshots ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "created_at TEXT NOT NULL,"
                    + "label TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS audit_logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "created_at TEXT NOT NULL,"
                    + "action TEXT NOT NULL,"
                    + "actor TEXT NOT NULL,"
                    + "ref_id TEXT,"
                    + "message TEXT NOT NULL)");

            statement.execute("CREATE INDEX IF NOT EXISTS idx_migration_type_created ON migration_records(type, created_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_profiles_name ON player_pet_profiles(player_name)");
        }
    }

    public synchronized long createSnapshot(String label) throws SQLException {
        MigrationSnapshot snapshot = dumpSnapshot();
        String sql = "INSERT INTO import_snapshots (created_at, label, payload_json) VALUES (?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, label);
            statement.setString(3, gson.toJson(snapshot, SNAPSHOT_TYPE));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to create snapshot");
    }

    public synchronized MigrationSnapshot loadSnapshot(long snapshotId) throws SQLException {
        String sql = "SELECT payload_json FROM import_snapshots WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, snapshotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return gson.fromJson(resultSet.getString(1), SNAPSHOT_TYPE);
                }
            }
        }
        throw new SQLException("Snapshot not found: " + snapshotId);
    }

    public synchronized MigrationSnapshot restoreSnapshot(long snapshotId, String importRecordId) throws SQLException {
        MigrationSnapshot snapshot = loadSnapshot(snapshotId);
        replaceAll(importRecordId, snapshot);
        return snapshot;
    }

    public synchronized MigrationRecord createMigrationRecord(String type,
                                                              String status,
                                                              String operatorName,
                                                              String summary,
                                                              Long rollbackSnapshotId,
                                                              String exportPath,
                                                              String reportPath,
                                                              String sourceHash) throws SQLException {
        String recordId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String sql = "INSERT INTO migration_records (record_id, created_at, updated_at, type, status, operator_name, summary, rollback_snapshot_id, export_path, report_path, source_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recordId);
            statement.setString(2, now.toString());
            statement.setString(3, now.toString());
            statement.setString(4, type);
            statement.setString(5, status);
            statement.setString(6, operatorName);
            statement.setString(7, summary == null ? "" : summary);
            if (rollbackSnapshotId == null) {
                statement.setObject(8, null);
            } else {
                statement.setLong(8, rollbackSnapshotId);
            }
            statement.setString(9, exportPath);
            statement.setString(10, reportPath);
            statement.setString(11, sourceHash);
            statement.executeUpdate();
        }
        return getMigrationRecord(recordId).orElseThrow(() -> new SQLException("Failed to reload migration record " + recordId));
    }

    public synchronized MigrationRecord updateMigrationRecord(String recordId,
                                                              String status,
                                                              String summary,
                                                              String exportPath,
                                                              String reportPath,
                                                              String sourceHash) throws SQLException {
        String sql = "UPDATE migration_records SET updated_at = ?, status = ?, summary = ?, "
                + "export_path = COALESCE(?, export_path), report_path = COALESCE(?, report_path), source_hash = COALESCE(?, source_hash) "
                + "WHERE record_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, status);
            statement.setString(3, summary == null ? "" : summary);
            statement.setString(4, exportPath);
            statement.setString(5, reportPath);
            statement.setString(6, sourceHash);
            statement.setString(7, recordId);
            statement.executeUpdate();
        }
        return getMigrationRecord(recordId).orElseThrow(() -> new SQLException("Migration record not found: " + recordId));
    }

    public synchronized Optional<MigrationRecord> getMigrationRecord(String recordId) throws SQLException {
        String sql = "SELECT * FROM migration_records WHERE record_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recordId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapMigrationRecord(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<MigrationRecord> getLatestMigrationRecord(String type) throws SQLException {
        String sql = "SELECT * FROM migration_records WHERE type = ? ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapMigrationRecord(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized void replaceAll(String importRecordId, MigrationSnapshot snapshot) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                clearCurrentTables(connection);
                insertPets(connection, importRecordId, snapshot.petDefinitions());
                insertCategories(connection, importRecordId, snapshot.categoryDefinitions());
                insertProfiles(connection, importRecordId, snapshot.playerProfiles());
                insertAssets(connection, importRecordId, snapshot.assets());
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public synchronized List<PetDefinition> loadPetDefinitions() throws SQLException {
        String sql = "SELECT payload_json FROM pet_definitions ORDER BY pet_id";
        List<PetDefinition> result = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(gson.fromJson(resultSet.getString(1), PET_TYPE));
            }
        }
        return result;
    }

    public synchronized List<CategoryDefinition> loadCategoryDefinitions() throws SQLException {
        String sql = "SELECT payload_json FROM category_definitions ORDER BY category_id";
        List<CategoryDefinition> result = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(gson.fromJson(resultSet.getString(1), CATEGORY_TYPE));
            }
        }
        return result;
    }

    public synchronized List<PlayerPetProfile> loadPlayerProfiles() throws SQLException {
        String sql = "SELECT payload_json FROM player_pet_profiles ORDER BY player_uuid";
        List<PlayerPetProfile> result = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(gson.fromJson(resultSet.getString(1), PROFILE_TYPE));
            }
        }
        return result;
    }

    public synchronized List<LegacyAssetBlob> loadAssets() throws SQLException {
        String sql = "SELECT payload_json FROM system_assets ORDER BY asset_key";
        List<LegacyAssetBlob> result = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(gson.fromJson(resultSet.getString(1), ASSET_TYPE));
            }
        }
        return result;
    }

    public synchronized boolean petExists(String petId) throws SQLException {
        String sql = "SELECT 1 FROM pet_definitions WHERE pet_id = ? LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, petId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public synchronized Optional<PetDefinition> findPetDefinition(String petId) throws SQLException {
        String sql = "SELECT payload_json FROM pet_definitions WHERE pet_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, petId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(gson.fromJson(resultSet.getString(1), PET_TYPE));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Set<String> listPetIds() throws SQLException {
        String sql = "SELECT pet_id FROM pet_definitions ORDER BY pet_id";
        Set<String> result = new TreeSet<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
        }
        return result;
    }

    public synchronized Optional<PlayerPetProfile> findPlayerProfile(UUID playerUuid) throws SQLException {
        String sql = "SELECT payload_json FROM player_pet_profiles WHERE player_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(gson.fromJson(resultSet.getString(1), PROFILE_TYPE));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized void upsertPlayerProfile(PlayerPetProfile profile, String importRecordId) throws SQLException {
        String sql = "INSERT INTO player_pet_profiles (player_uuid, player_name, active_pet_id, owned_pet_ids_json, payload_json, import_record_id, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, active_pet_id = excluded.active_pet_id, "
                + "owned_pet_ids_json = excluded.owned_pet_ids_json, payload_json = excluded.payload_json, import_record_id = excluded.import_record_id, updated_at = excluded.updated_at";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.playerUuid().toString());
            statement.setString(2, profile.playerName() == null ? "" : profile.playerName());
            statement.setString(3, profile.activePetId());
            statement.setString(4, gson.toJson(profile.ownedPetIds()));
            statement.setString(5, gson.toJson(profile, PROFILE_TYPE));
            statement.setString(6, importRecordId);
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public synchronized void addAudit(String action, String actor, String refId, String message) throws SQLException {
        String sql = "INSERT INTO audit_logs (created_at, action, actor, ref_id, message) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, action);
            statement.setString(3, actor == null ? "SYSTEM" : actor);
            statement.setString(4, refId);
            statement.setString(5, message == null ? "" : message);
            statement.executeUpdate();
        }
    }

    private MigrationSnapshot dumpSnapshot() throws SQLException {
        return new MigrationSnapshot(loadPetDefinitions(), loadCategoryDefinitions(), loadPlayerProfiles(), loadAssets());
    }

    private void clearCurrentTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM pet_definitions");
            statement.executeUpdate("DELETE FROM category_definitions");
            statement.executeUpdate("DELETE FROM player_pet_profiles");
            statement.executeUpdate("DELETE FROM system_assets");
        }
    }

    private void insertPets(Connection connection, String importRecordId, List<PetDefinition> petDefinitions) throws SQLException {
        String sql = "INSERT INTO pet_definitions (pet_id, legacy_id, permission_node, category_ids_json, payload_json, import_record_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (PetDefinition petDefinition : petDefinitions) {
                statement.setString(1, petDefinition.petId());
                statement.setString(2, petDefinition.legacyId());
                statement.setString(3, petDefinition.permissionNode());
                statement.setString(4, gson.toJson(petDefinition.categoryIds()));
                statement.setString(5, gson.toJson(petDefinition, PET_TYPE));
                statement.setString(6, importRecordId);
                statement.setString(7, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCategories(Connection connection, String importRecordId, List<CategoryDefinition> categoryDefinitions) throws SQLException {
        String sql = "INSERT INTO category_definitions (category_id, payload_json, import_record_id, updated_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (CategoryDefinition categoryDefinition : categoryDefinitions) {
                statement.setString(1, categoryDefinition.categoryId());
                statement.setString(2, gson.toJson(categoryDefinition, CATEGORY_TYPE));
                statement.setString(3, importRecordId);
                statement.setString(4, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertProfiles(Connection connection, String importRecordId, List<PlayerPetProfile> profiles) throws SQLException {
        String sql = "INSERT INTO player_pet_profiles (player_uuid, player_name, active_pet_id, owned_pet_ids_json, payload_json, import_record_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (PlayerPetProfile profile : profiles) {
                statement.setString(1, profile.playerUuid().toString());
                statement.setString(2, profile.playerName() == null ? "" : profile.playerName());
                statement.setString(3, profile.activePetId());
                statement.setString(4, gson.toJson(profile.ownedPetIds()));
                statement.setString(5, gson.toJson(profile, PROFILE_TYPE));
                statement.setString(6, importRecordId);
                statement.setString(7, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAssets(Connection connection, String importRecordId, List<LegacyAssetBlob> assets) throws SQLException {
        String sql = "INSERT INTO system_assets (asset_key, payload_json, import_record_id, updated_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (LegacyAssetBlob asset : assets) {
                statement.setString(1, asset.assetKey());
                statement.setString(2, gson.toJson(asset, ASSET_TYPE));
                statement.setString(3, importRecordId);
                statement.setString(4, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private MigrationRecord mapMigrationRecord(ResultSet resultSet) throws SQLException {
        Long rollbackSnapshotId = resultSet.getObject("rollback_snapshot_id") == null ? null : resultSet.getLong("rollback_snapshot_id");
        return new MigrationRecord(
                resultSet.getString("record_id"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getString("type"),
                resultSet.getString("status"),
                resultSet.getString("operator_name"),
                resultSet.getString("summary"),
                rollbackSnapshotId,
                resultSet.getString("export_path"),
                resultSet.getString("report_path"),
                resultSet.getString("source_hash")
        );
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public void close() {
    }
}
