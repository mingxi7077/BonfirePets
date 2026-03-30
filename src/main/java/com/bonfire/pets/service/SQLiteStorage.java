package com.bonfire.pets.service;

import com.bonfire.pets.model.BackupRunSummary;
import com.bonfire.pets.model.RollbackJobRecord;
import com.bonfire.pets.model.SnapshotRecord;
import com.bonfire.pets.model.StorageStats;
import com.bonfire.pets.model.SourcePlayerRecord;

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
import java.util.UUID;

public final class SQLiteStorage {

    private final Path sqlitePath;
    private final String jdbcUrl;

    public SQLiteStorage(Path sqlitePath) {
        this.sqlitePath = sqlitePath;
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();
    }

    public Path sqlitePath() {
        return sqlitePath;
    }

    public synchronized void initialize() throws SQLException {
        try {
            Files.createDirectories(sqlitePath.toAbsolutePath().getParent());
        } catch (Exception exception) {
            throw new SQLException("Failed to create sqlite directory: " + sqlitePath, exception);
        }

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");

            statement.execute("CREATE TABLE IF NOT EXISTS snapshots ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL DEFAULT '',"
                    + "source_type TEXT NOT NULL,"
                    + "snapshot_type TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL,"
                    + "names_raw TEXT NOT NULL,"
                    + "inventories_raw TEXT NOT NULL,"
                    + "data_raw TEXT NOT NULL,"
                    + "content_hash TEXT NOT NULL,"
                    + "note TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_player_uuid ON snapshots(player_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_player_name ON snapshots(player_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_created_at ON snapshots(created_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS backup_runs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "run_type TEXT NOT NULL,"
                    + "started_at TEXT NOT NULL,"
                    + "finished_at TEXT,"
                    + "scanned_count INTEGER NOT NULL DEFAULT 0,"
                    + "inserted_count INTEGER NOT NULL DEFAULT 0,"
                    + "skipped_count INTEGER NOT NULL DEFAULT 0,"
                    + "status TEXT NOT NULL,"
                    + "message TEXT)");

            statement.execute("CREATE TABLE IF NOT EXISTS rollback_jobs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "job_uuid TEXT NOT NULL UNIQUE,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL DEFAULT '',"
                    + "target_snapshot_id INTEGER NOT NULL,"
                    + "pre_snapshot_id INTEGER,"
                    + "operator_name TEXT NOT NULL,"
                    + "status TEXT NOT NULL,"
                    + "message TEXT,"
                    + "created_at TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");

            statement.execute("CREATE TABLE IF NOT EXISTS audit_logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_type TEXT NOT NULL,"
                    + "player_uuid TEXT,"
                    + "operator_name TEXT,"
                    + "ref_id TEXT,"
                    + "message TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL)");
        }
    }

    public synchronized long startBackupRun(String runType) throws SQLException {
        String sql = "INSERT INTO backup_runs (run_type, started_at, status, message) VALUES (?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, runType);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, "RUNNING");
            statement.setString(4, "started");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to create backup run record");
    }

    public synchronized void finishBackupRun(long runId, BackupRunSummary summary) throws SQLException {
        String sql = "UPDATE backup_runs SET finished_at = ?, scanned_count = ?, inserted_count = ?, skipped_count = ?, status = ?, message = ? WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setInt(2, summary.scannedCount());
            statement.setInt(3, summary.insertedCount());
            statement.setInt(4, summary.skippedCount());
            statement.setString(5, summary.status());
            statement.setString(6, summary.message());
            statement.setLong(7, runId);
            statement.executeUpdate();
        }
    }

    public synchronized Optional<String> findLatestHash(UUID playerUuid) throws SQLException {
        String sql = "SELECT content_hash FROM snapshots WHERE player_uuid = ? ORDER BY id DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<String> findLatestPlayerName(UUID playerUuid) throws SQLException {
        String sql = "SELECT player_name FROM snapshots WHERE player_uuid = ? AND player_name <> '' ORDER BY id DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<UUID> findLatestUuidByPlayerName(String playerName) throws SQLException {
        String sql = "SELECT player_uuid FROM snapshots WHERE player_name = ? COLLATE NOCASE ORDER BY id DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(UUID.fromString(resultSet.getString(1)));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized long insertSnapshot(SourcePlayerRecord record, String snapshotType, String sourceType, String note) throws SQLException {
        String sql = "INSERT INTO snapshots (player_uuid, player_name, source_type, snapshot_type, created_at, names_raw, inventories_raw, data_raw, content_hash, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, record.playerUuid().toString());
            statement.setString(2, record.playerName());
            statement.setString(3, sourceType);
            statement.setString(4, snapshotType);
            statement.setString(5, Instant.now().toString());
            statement.setString(6, record.namesRaw());
            statement.setString(7, record.inventoriesRaw());
            statement.setString(8, record.dataRaw());
            statement.setString(9, record.contentHash());
            statement.setString(10, note == null ? "" : note);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to insert snapshot");
    }

    public synchronized List<SnapshotRecord> listSnapshotsByUuid(UUID playerUuid, int page, int pageSize) throws SQLException {
        String sql = "SELECT * FROM snapshots WHERE player_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        List<SnapshotRecord> snapshots = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, pageSize);
            statement.setInt(3, Math.max(0, page - 1) * pageSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    snapshots.add(mapSnapshot(resultSet));
                }
            }
        }
        return snapshots;
    }

    public synchronized long countSnapshotsByUuid(UUID playerUuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM snapshots WHERE player_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        }
        return 0L;
    }

    public synchronized Optional<SnapshotRecord> getSnapshot(long id) throws SQLException {
        String sql = "SELECT * FROM snapshots WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapSnapshot(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized int cleanupSnapshotsOlderThan(Instant cutoff) throws SQLException {
        String sql = "DELETE FROM snapshots WHERE created_at < ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cutoff.toString());
            return statement.executeUpdate();
        }
    }

    public synchronized String createRollbackJob(UUID playerUuid, String playerName, long targetSnapshotId, String operatorName, String message) throws SQLException {
        String jobUuid = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String sql = "INSERT INTO rollback_jobs (job_uuid, player_uuid, player_name, target_snapshot_id, operator_name, status, message, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobUuid);
            statement.setString(2, playerUuid.toString());
            statement.setString(3, playerName == null ? "" : playerName);
            statement.setLong(4, targetSnapshotId);
            statement.setString(5, operatorName);
            statement.setString(6, "PENDING");
            statement.setString(7, message == null ? "" : message);
            statement.setString(8, now);
            statement.setString(9, now);
            statement.executeUpdate();
        }
        return jobUuid;
    }

    public synchronized void updateRollbackJob(String jobUuid, String status, String message, Long preSnapshotId) throws SQLException {
        String sql = "UPDATE rollback_jobs SET status = ?, message = ?, pre_snapshot_id = COALESCE(?, pre_snapshot_id), updated_at = ? WHERE job_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, message == null ? "" : message);
            if (preSnapshotId == null) {
                statement.setObject(3, null);
            } else {
                statement.setLong(3, preSnapshotId);
            }
            statement.setString(4, Instant.now().toString());
            statement.setString(5, jobUuid);
            statement.executeUpdate();
        }
    }

    public synchronized Optional<RollbackJobRecord> getRollbackJob(String jobUuid) throws SQLException {
        String sql = "SELECT * FROM rollback_jobs WHERE job_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    Long preSnapshotId = resultSet.getObject("pre_snapshot_id") == null ? null : resultSet.getLong("pre_snapshot_id");
                    return Optional.of(new RollbackJobRecord(
                            resultSet.getString("job_uuid"),
                            UUID.fromString(resultSet.getString("player_uuid")),
                            resultSet.getString("player_name"),
                            resultSet.getLong("target_snapshot_id"),
                            preSnapshotId,
                            resultSet.getString("operator_name"),
                            resultSet.getString("status"),
                            resultSet.getString("message"),
                            Instant.parse(resultSet.getString("created_at")),
                            Instant.parse(resultSet.getString("updated_at"))
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized void addAudit(String eventType, UUID playerUuid, String operatorName, String refId, String message) throws SQLException {
        String sql = "INSERT INTO audit_logs (event_type, player_uuid, operator_name, ref_id, message, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            statement.setString(2, playerUuid == null ? null : playerUuid.toString());
            statement.setString(3, operatorName);
            statement.setString(4, refId);
            statement.setString(5, message);
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public synchronized StorageStats getStorageStats() throws SQLException {
        long snapshotCount = countTable("snapshots");
        long rollbackJobCount = countTable("rollback_jobs");
        long auditLogCount = countTable("audit_logs");
        BackupRunSummary lastBackupRun = getLastBackupRun();
        return new StorageStats(snapshotCount, rollbackJobCount, auditLogCount, lastBackupRun);
    }

    private long countTable(String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
        }
        return 0L;
    }

    private BackupRunSummary getLastBackupRun() throws SQLException {
        String sql = "SELECT id, scanned_count, inserted_count, skipped_count, status, message FROM backup_runs ORDER BY id DESC LIMIT 1";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return new BackupRunSummary(
                        resultSet.getLong("id"),
                        resultSet.getInt("scanned_count"),
                        resultSet.getInt("inserted_count"),
                        resultSet.getInt("skipped_count"),
                        resultSet.getString("status"),
                        resultSet.getString("message")
                );
            }
        }
        return null;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private SnapshotRecord mapSnapshot(ResultSet resultSet) throws SQLException {
        return new SnapshotRecord(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("source_type"),
                resultSet.getString("snapshot_type"),
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getString("names_raw"),
                resultSet.getString("inventories_raw"),
                resultSet.getString("data_raw"),
                resultSet.getString("content_hash"),
                resultSet.getString("note")
        );
    }
}

