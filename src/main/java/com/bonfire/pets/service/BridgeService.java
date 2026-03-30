package com.bonfire.pets.service;

import com.bonfire.pets.BonfirePets;
import com.bonfire.pets.config.BridgeConfig;
import com.bonfire.pets.model.BackupRunSummary;
import com.bonfire.pets.model.CleanupSummary;
import com.bonfire.pets.model.PendingRollbackRequest;
import com.bonfire.pets.model.PetDefinitionCatalog;
import com.bonfire.pets.model.PlayerReference;
import com.bonfire.pets.model.ResolvedSourceConfig;
import com.bonfire.pets.model.RollbackJobRecord;
import com.bonfire.pets.model.RollbackRiskReport;
import com.bonfire.pets.model.SnapshotRecord;
import com.bonfire.pets.model.SourceCompatibilityReport;
import com.bonfire.pets.model.SourcePlayerRecord;
import com.bonfire.pets.model.StorageStats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class BridgeService implements AutoCloseable {

    private final BonfirePets plugin;
    private final Logger logger;
    private final Map<String, PendingRollbackRequest> pendingRollbackRequests = new ConcurrentHashMap<>();
    private final MCPetsPetDefinitionScanner petDefinitionScanner = new MCPetsPetDefinitionScanner();
    private final MCPetsSnapshotRiskInspector snapshotRiskInspector = new MCPetsSnapshotRiskInspector();

    private BridgeConfig config;
    private SQLiteStorage storage;
    private ResolvedSourceConfig resolvedSourceConfig;
    private MCPetsMysqlSource source;
    private volatile SourceCompatibilityReport sourceCompatibilityReport = SourceCompatibilityReport.unknown("bridge not initialized");
    private volatile PetDefinitionCatalog petDefinitionCatalog = PetDefinitionCatalog.unavailable("", "pet definitions not scanned yet", Instant.EPOCH);

    public BridgeService(BonfirePets plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void reload(BridgeConfig config) throws SQLException {
        close();
        this.config = config;

        Path sqlitePath = config.resolveSqlitePath(plugin);
        this.storage = new SQLiteStorage(sqlitePath);
        this.storage.initialize();

        MCPetsConfigAutoDetect autoDetect = new MCPetsConfigAutoDetect();
        this.resolvedSourceConfig = autoDetect.resolve(plugin, config.source(), config.resolveMcpetsConfigPath(plugin));
        this.source = new MCPetsMysqlSource(resolvedSourceConfig);
        this.pendingRollbackRequests.clear();

        SourceCompatibilityReport compatibility = refreshSourceCompatibility();
        if (compatibility.readOnly()) {
            logger.warning("BonfirePets entered read-only mode: " + compatibility.reason());
        }

        PetDefinitionCatalog catalog = refreshPetDefinitionCatalog();
        if (!catalog.available()) {
            logger.warning("BonfirePets pet definition index unavailable: " + catalog.reason());
        }
    }

    public BridgeConfig config() {
        return config;
    }

    public List<String> statusLines() {
        purgeExpiredRollbackRequests();
        SourceCompatibilityReport compatibility = refreshSourceCompatibility();
        PetDefinitionCatalog catalog = refreshPetDefinitionCatalog();

        List<String> lines = new ArrayList<>();
        lines.add("[BMCB] version=0.3.0 enabled=" + config.enabled());
        lines.add("[BMCB] sqlite=" + storage.sqlitePath());
        lines.add("[BMCB] source=" + resolvedSourceConfig.description());
        lines.add("[BMCB] sourceGuard schema=" + compatibility.schemaCompatible()
                + " readOnly=" + compatibility.readOnly()
                + " checked=" + compatibility.checkedAt()
                + " columns=" + summarizeValues(compatibility.tableColumns(), 8)
                + " missing=" + summarizeValues(compatibility.missingRequiredColumns(), 8)
                + " reason=" + safeText(compatibility.reason()));
        lines.add("[BMCB] petDefs available=" + catalog.available()
                + " count=" + catalog.petCount()
                + " scanned=" + catalog.scannedAt()
                + " dir=" + safeText(catalog.petsDirectory())
                + " reason=" + safeText(catalog.reason()));
        lines.add("[BMCB] backup interval=" + config.backup().intervalMinutes() + "m retention=" + config.backup().retentionDays() + "d onlyChanged=" + config.backup().onlyWhenChanged());
        lines.add("[BMCB] rollback requireOffline=" + config.rollback().requirePlayerOffline()
                + " verify=" + config.rollback().verifyAfterApply()
                + " confirm=" + config.rollback().requireConfirmation()
                + " ttl=" + config.rollback().confirmationTtlSeconds() + "s"
                + " pending=" + pendingRollbackRequests.size());

        try {
            StorageStats stats = storage.getStorageStats();
            String sourcePlayersText = compatibility.schemaCompatible() ? Long.toString(source.countPlayers()) : "blocked";
            lines.add("[BMCB] storage snapshots=" + stats.snapshotCount()
                    + " jobs=" + stats.rollbackJobCount()
                    + " audits=" + stats.auditLogCount()
                    + " sourcePlayers=" + sourcePlayersText);
            if (stats.lastBackupRun() != null) {
                BackupRunSummary lastRun = stats.lastBackupRun();
                lines.add("[BMCB] lastRun id=" + lastRun.runId()
                        + " status=" + lastRun.status()
                        + " scanned=" + lastRun.scannedCount()
                        + " inserted=" + lastRun.insertedCount()
                        + " skipped=" + lastRun.skippedCount()
                        + " message=" + lastRun.message());
            }
        } catch (Exception exception) {
            lines.add("[BMCB] storage status unavailable: " + exception.getMessage());
        }

        if (config.status().showSoftdepends()) {
            lines.add("[BMCB] softdepends " + pluginState("MCPets")
                    + " | " + pluginState("NBTAPI")
                    + " | " + pluginState("ItemsAdder")
                    + " | " + pluginState("MMOCore")
                    + " | " + pluginState("MythicMobs")
                    + " | " + pluginState("ModelEngine")
                    + " | " + pluginState("CMI"));
        }
        return lines;
    }

    public PlayerReference resolvePlayerReference(String input) throws SQLException {
        try {
            UUID uuid = UUID.fromString(input);
            return new PlayerReference(uuid, resolvePlayerName(uuid, ""));
        } catch (IllegalArgumentException ignored) {
        }

        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new PlayerReference(online.getUniqueId(), online.getName());
        }

        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(input);
        if (cached != null) {
            return new PlayerReference(cached.getUniqueId(), cached.getName() == null ? input : cached.getName());
        }

        Optional<UUID> latestUuid = storage.findLatestUuidByPlayerName(input);
        if (latestUuid.isPresent()) {
            UUID uuid = latestUuid.get();
            return new PlayerReference(uuid, resolvePlayerName(uuid, input));
        }

        return null;
    }

    public BackupRunSummary runScheduledBackup() {
        return runBackup(null, "scheduled", "SYSTEM");
    }

    public BackupRunSummary runManualBackup(PlayerReference target, String operatorName) {
        return runBackup(target, "manual", operatorName);
    }

    public List<SnapshotRecord> listSnapshots(PlayerReference playerReference, int page, int pageSize) throws SQLException {
        return storage.listSnapshotsByUuid(playerReference.uuid(), Math.max(1, page), Math.max(1, pageSize));
    }

    public long countSnapshots(PlayerReference playerReference) throws SQLException {
        return storage.countSnapshotsByUuid(playerReference.uuid());
    }

    public Optional<SnapshotRecord> getSnapshot(long snapshotId) throws SQLException {
        return storage.getSnapshot(snapshotId);
    }

    public CleanupSummary runCleanup() {
        try {
            purgeExpiredRollbackRequests();
            int deleted = storage.cleanupSnapshotsOlderThan(Instant.now().minus(config.backup().retentionDays(), ChronoUnit.DAYS));
            storage.addAudit("cleanup", null, "SYSTEM", null, "Deleted snapshots=" + deleted);
            return new CleanupSummary(deleted, "Deleted snapshots=" + deleted);
        } catch (Exception exception) {
            logger.warning("Cleanup failed: " + exception.getMessage());
            return new CleanupSummary(0, "Cleanup failed: " + exception.getMessage());
        }
    }

    public String previewRollback(PlayerReference playerReference, long snapshotId, String operatorName) {
        try {
            purgeExpiredRollbackRequests();
            Optional<SnapshotRecord> snapshotOptional = storage.getSnapshot(snapshotId);
            if (snapshotOptional.isEmpty()) {
                return "Rollback refused: snapshot not found.";
            }

            SnapshotRecord snapshot = snapshotOptional.get();
            if (!snapshot.playerUuid().equals(playerReference.uuid())) {
                return "Rollback refused: player does not match snapshot owner.";
            }

            if (config.rollback().requirePlayerOffline() && Bukkit.getPlayer(playerReference.uuid()) != null) {
                return "Rollback refused: target player is online.";
            }

            SourceCompatibilityReport compatibility = refreshSourceCompatibility();
            if (compatibility.readOnly()) {
                return "Rollback refused: source read-only mode is active. " + compatibility.reason();
            }

            RollbackRiskReport riskReport = inspectRollbackRisk(snapshot);
            if (!riskReport.safe()) {
                return "Rollback refused: " + riskReport.reason();
            }

            String token = createRollbackToken();
            Instant expiresAt = Instant.now().plusSeconds(config.rollback().confirmationTtlSeconds());
            String preview = "Rollback preview token=" + token
                    + " player=" + resolvePlayerName(playerReference.uuid(), playerReference.name())
                    + " snapshot=" + snapshot.id()
                    + " created=" + snapshot.createdAt()
                    + " hash=" + shortHash(snapshot.contentHash())
                    + " pets=" + summarizeValues(riskReport.referencedPetIds(), 6)
                    + " expires=" + expiresAt;
            PendingRollbackRequest request = new PendingRollbackRequest(token, playerReference, snapshotId, operatorName, expiresAt, preview);
            pendingRollbackRequests.put(token, request);
            return preview + " | confirm with /bpet rollback confirm " + token;
        } catch (Exception exception) {
            logger.warning("Rollback preview failed: " + exception.getMessage());
            return "Rollback preview failed: " + exception.getMessage();
        }
    }

    public String confirmRollback(String token, String operatorName) {
        purgeExpiredRollbackRequests();
        String normalizedToken = token.toUpperCase(Locale.ROOT);
        PendingRollbackRequest request = pendingRollbackRequests.get(normalizedToken);
        if (request == null) {
            return "Rollback confirmation failed: token not found or expired.";
        }
        if (!request.operatorName().equalsIgnoreCase(operatorName)) {
            return "Rollback confirmation failed: token belongs to another operator.";
        }

        pendingRollbackRequests.remove(normalizedToken);
        return executeRollback(request.player(), request.snapshotId(), operatorName);
    }

    public String rollback(PlayerReference playerReference, long snapshotId, String operatorName) {
        return executeRollback(playerReference, snapshotId, operatorName);
    }

    public Optional<RollbackJobRecord> getRollbackJob(String jobUuid) throws SQLException {
        return storage.getRollbackJob(jobUuid);
    }

    private String executeRollback(PlayerReference playerReference, long snapshotId, String operatorName) {
        String jobUuid = null;
        Long preSnapshotId = null;
        try {
            Optional<SnapshotRecord> snapshotOptional = storage.getSnapshot(snapshotId);
            if (snapshotOptional.isEmpty()) {
                return "Rollback refused: snapshot not found.";
            }

            SnapshotRecord snapshot = snapshotOptional.get();
            if (!snapshot.playerUuid().equals(playerReference.uuid())) {
                return "Rollback refused: player does not match snapshot owner.";
            }

            if (config.rollback().requirePlayerOffline() && Bukkit.getPlayer(playerReference.uuid()) != null) {
                return "Rollback refused: target player is online.";
            }

            SourceCompatibilityReport compatibility = refreshSourceCompatibility();
            if (compatibility.readOnly()) {
                String refusal = "Rollback refused: source read-only mode is active. " + compatibility.reason();
                storage.addAudit("rollback_refused", playerReference.uuid(), operatorName, Long.toString(snapshotId), refusal);
                return refusal;
            }

            RollbackRiskReport riskReport = inspectRollbackRisk(snapshot);
            if (!riskReport.safe()) {
                String refusal = "Rollback refused: " + riskReport.reason();
                storage.addAudit("rollback_refused", playerReference.uuid(), operatorName, Long.toString(snapshotId), refusal);
                return refusal;
            }

            String playerName = resolvePlayerName(playerReference.uuid(), playerReference.name());
            jobUuid = storage.createRollbackJob(playerReference.uuid(), playerName, snapshotId, operatorName, "rollback requested");
            storage.addAudit("rollback_requested", playerReference.uuid(), operatorName, jobUuid, "snapshot=" + snapshotId);

            Optional<SourcePlayerRecord> currentOptional = source.loadPlayer(playerReference.uuid());
            if (config.rollback().createPreRollbackSnapshot()) {
                SourcePlayerRecord current = currentOptional.orElse(new SourcePlayerRecord(playerReference.uuid(), playerName, "", "", ""));
                preSnapshotId = storage.insertSnapshot(withResolvedName(current), "pre_rollback", "mysql", "Pre-rollback snapshot for job " + jobUuid);
            }

            storage.updateRollbackJob(jobUuid, "VALIDATED", "validation passed; pets=" + summarizeValues(riskReport.referencedPetIds(), 6), preSnapshotId);
            source.upsertPlayer(snapshot.toSourcePlayerRecord());
            storage.updateRollbackJob(jobUuid, "APPLIED", "snapshot written to source", preSnapshotId);

            if (config.rollback().verifyAfterApply()) {
                Optional<SourcePlayerRecord> reloadedOptional = source.loadPlayer(playerReference.uuid());
                if (reloadedOptional.isEmpty()) {
                    storage.updateRollbackJob(jobUuid, "FAILED", "verification failed: source row missing after write", preSnapshotId);
                    storage.addAudit("rollback_failed", playerReference.uuid(), operatorName, jobUuid, "verification failed: row missing");
                    return "Rollback failed. job=" + jobUuid + " reason=source row missing after write";
                }

                SourcePlayerRecord reloaded = withResolvedName(reloadedOptional.get());
                if (!snapshot.contentHash().equals(reloaded.contentHash())) {
                    storage.updateRollbackJob(jobUuid, "FAILED", "verification failed: hash mismatch", preSnapshotId);
                    storage.addAudit("rollback_failed", playerReference.uuid(), operatorName, jobUuid, "verification failed: hash mismatch");
                    return "Rollback failed. job=" + jobUuid + " reason=hash mismatch";
                }
            }

            storage.updateRollbackJob(jobUuid, "VERIFIED", "rollback completed", preSnapshotId);
            storage.addAudit("rollback_verified", playerReference.uuid(), operatorName, jobUuid, "snapshot=" + snapshotId);
            return "Rollback completed. job=" + jobUuid + " snapshot=" + snapshotId;
        } catch (Exception exception) {
            logger.warning("Rollback failed: " + exception.getMessage());
            if (jobUuid != null) {
                try {
                    storage.updateRollbackJob(jobUuid, "FAILED", exception.getMessage(), preSnapshotId);
                    storage.addAudit("rollback_failed", playerReference.uuid(), operatorName, jobUuid, exception.getMessage());
                } catch (SQLException ignored) {
                }
            }
            return "Rollback failed: " + exception.getMessage();
        }
    }

    private BackupRunSummary runBackup(PlayerReference target, String runType, String operatorName) {
        long runId = -1L;
        int scanned = 0;
        int inserted = 0;
        int skipped = 0;

        try {
            runId = storage.startBackupRun(runType);

            SourceCompatibilityReport compatibility = refreshSourceCompatibility();
            if (!compatibility.schemaCompatible()) {
                BackupRunSummary summary = new BackupRunSummary(runId, 0, 0, 0, "FAILED", "Source guard active: " + compatibility.reason());
                storage.finishBackupRun(runId, summary);
                storage.addAudit("backup_failed", target == null ? null : target.uuid(), operatorName, Long.toString(runId), summary.message());
                return summary;
            }

            List<SourcePlayerRecord> sourceRecords = target == null ? loadBackupTargets() : loadSingleBackupTarget(target);

            for (SourcePlayerRecord sourceRecord : sourceRecords) {
                scanned++;
                SourcePlayerRecord resolvedRecord = withResolvedName(sourceRecord);
                Optional<String> latestHash = storage.findLatestHash(resolvedRecord.playerUuid());
                if (config.backup().onlyWhenChanged() && latestHash.isPresent() && latestHash.get().equals(resolvedRecord.contentHash())) {
                    skipped++;
                    continue;
                }

                storage.insertSnapshot(resolvedRecord, runType, "mysql", runType + " backup by " + operatorName);
                inserted++;
            }

            BackupRunSummary summary = new BackupRunSummary(runId, scanned, inserted, skipped, "SUCCESS", "Backup completed");
            storage.finishBackupRun(runId, summary);
            storage.addAudit("backup", target == null ? null : target.uuid(), operatorName, Long.toString(runId), "scanned=" + scanned + " inserted=" + inserted + " skipped=" + skipped);
            return summary;
        } catch (Exception exception) {
            logger.warning("Backup failed: " + exception.getMessage());
            BackupRunSummary summary = new BackupRunSummary(runId, scanned, inserted, skipped, "FAILED", exception.getMessage());
            if (runId > 0) {
                try {
                    storage.finishBackupRun(runId, summary);
                    storage.addAudit("backup_failed", target == null ? null : target.uuid(), operatorName, Long.toString(runId), exception.getMessage());
                } catch (SQLException ignored) {
                }
            }
            return summary;
        }
    }

    private List<SourcePlayerRecord> loadBackupTargets() throws SQLException {
        if (!config.backup().includeOnlinePlayersOnly()) {
            return source.loadAllPlayers();
        }

        List<SourcePlayerRecord> records = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            source.loadPlayer(player.getUniqueId()).ifPresent(records::add);
        }
        return records;
    }

    private List<SourcePlayerRecord> loadSingleBackupTarget(PlayerReference target) throws SQLException {
        List<SourcePlayerRecord> records = new ArrayList<>();
        Optional<SourcePlayerRecord> record = source.loadPlayer(target.uuid());
        record.ifPresent(records::add);
        return records;
    }

    private SourcePlayerRecord withResolvedName(SourcePlayerRecord record) throws SQLException {
        String name = resolvePlayerName(record.playerUuid(), record.playerName());
        return new SourcePlayerRecord(record.playerUuid(), name, record.namesRaw(), record.inventoriesRaw(), record.dataRaw());
    }

    private String resolvePlayerName(UUID playerUuid, String fallback) throws SQLException {
        Player online = Bukkit.getPlayer(playerUuid);
        if (online != null) {
            return online.getName();
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
            return offlinePlayer.getName();
        }

        Optional<String> latestSnapshotName = storage.findLatestPlayerName(playerUuid);
        if (latestSnapshotName.isPresent()) {
            return latestSnapshotName.get();
        }

        return fallback == null ? "" : fallback;
    }

    private SourceCompatibilityReport refreshSourceCompatibility() {
        if (source == null || resolvedSourceConfig == null) {
            sourceCompatibilityReport = SourceCompatibilityReport.unknown("source not initialized");
            return sourceCompatibilityReport;
        }

        try {
            sourceCompatibilityReport = source.inspectCompatibility();
        } catch (Exception exception) {
            sourceCompatibilityReport = SourceCompatibilityReport.readOnly(
                    resolvedSourceConfig.tableName(),
                    "Source inspection failed: " + exception.getMessage(),
                    List.of(),
                    List.of(),
                    Instant.now()
            );
        }
        return sourceCompatibilityReport;
    }

    private PetDefinitionCatalog refreshPetDefinitionCatalog() {
        if (config == null) {
            petDefinitionCatalog = PetDefinitionCatalog.unavailable("", "bridge not initialized", Instant.now());
            return petDefinitionCatalog;
        }

        Path configPath;
        try {
            configPath = config.resolveMcpetsConfigPath(plugin);
        } catch (Exception exception) {
            petDefinitionCatalog = PetDefinitionCatalog.unavailable("", "MCPets config path invalid: " + exception.getMessage(), Instant.now());
            return petDefinitionCatalog;
        }

        petDefinitionCatalog = petDefinitionScanner.scan(configPath);
        return petDefinitionCatalog;
    }

    private RollbackRiskReport inspectRollbackRisk(SnapshotRecord snapshot) {
        PetDefinitionCatalog catalog = refreshPetDefinitionCatalog();
        return snapshotRiskInspector.inspect(snapshot, catalog);
    }

    private void purgeExpiredRollbackRequests() {
        Instant now = Instant.now();
        pendingRollbackRequests.values().removeIf(request -> request.isExpired(now));
    }

    private String createRollbackToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        } while (pendingRollbackRequests.containsKey(token));
        return token;
    }

    private String shortHash(String hash) {
        if (hash == null || hash.length() < 12) {
            return hash;
        }
        return hash.substring(0, 12);
    }

    private String pluginState(String pluginName) {
        Plugin softPlugin = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (softPlugin == null) {
            return pluginName + "=missing";
        }
        return pluginName + "=" + softPlugin.getDescription().getVersion();
    }

    private String summarizeValues(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        if (values.size() <= limit) {
            return String.join(",", values);
        }
        return String.join(",", values.subList(0, limit)) + ",+" + (values.size() - limit) + " more";
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    @Override
    public void close() {
        pendingRollbackRequests.clear();
        sourceCompatibilityReport = SourceCompatibilityReport.unknown("bridge closed");
        petDefinitionCatalog = PetDefinitionCatalog.unavailable("", "bridge closed", Instant.now());
        if (source != null) {
            source.close();
            source = null;
        }
    }
}
