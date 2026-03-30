package com.bonfire.pets.runtime;

import com.bonfire.pets.BonfirePets;
import com.bonfire.pets.adapter.BetterModelAdapter;
import com.bonfire.pets.adapter.MythicMobsRuntimeAdapter;
import com.bonfire.pets.config.BonfirePetsConfig;
import com.bonfire.pets.importer.LegacyImportPipeline;
import com.bonfire.pets.model.BetterModelProbe;
import com.bonfire.pets.model.GiveResult;
import com.bonfire.pets.model.LegacyImportBundle;
import com.bonfire.pets.model.LegacyImportResult;
import com.bonfire.pets.model.MigrationRecord;
import com.bonfire.pets.model.MigrationSnapshot;
import com.bonfire.pets.model.PetDefinition;
import com.bonfire.pets.model.PlayerPetProfile;
import com.bonfire.pets.model.RollbackResult;
import com.bonfire.pets.model.ValidationIssue;
import com.bonfire.pets.model.ValidationReport;
import com.bonfire.pets.storage.BonfirePetsStorage;
import com.bonfire.pets.util.HashUtil;
import com.bonfire.pets.validation.MythicMobsAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BonfirePetsService implements AutoCloseable {

    private final BonfirePets plugin;
    private final BonfirePetsConfig config;
    private final BonfirePetsStorage storage;
    private final BetterModelAdapter betterModelAdapter;
    private final LegacyImportPipeline legacyImportPipeline;
    private final MythicMobsRuntimeAdapter mythicMobsRuntimeAdapter;
    private final PetRuntimeManager runtimeManager;
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) ->
                    value == null ? null : new JsonPrimitive(value.toString()))
            .create();

    public BonfirePetsService(BonfirePets plugin, BonfirePetsConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new BonfirePetsStorage(config.resolveSqlitePath(plugin));
        MythicMobsAdapter mythicMobsAdapter = new MythicMobsAdapter();
        this.betterModelAdapter = new BetterModelAdapter(plugin, config.betterModel().registerEventHooks());
        this.legacyImportPipeline = new LegacyImportPipeline(plugin, config, mythicMobsAdapter);
        this.mythicMobsRuntimeAdapter = new MythicMobsRuntimeAdapter();
        this.runtimeManager = new PetRuntimeManager(plugin, storage, betterModelAdapter, mythicMobsRuntimeAdapter);
    }

    public void initialize() throws Exception {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.createDirectories(config.resolveReportsDir(plugin));
        Files.createDirectories(config.resolveExportsDir(plugin));
        storage.initialize();
        betterModelAdapter.initialize();
        runtimeManager.initialize();
    }

    public BonfirePetsConfig config() {
        return config;
    }

    public LegacyImportResult runImport(String operatorName) throws Exception {
        LegacyImportBundle bundle = legacyImportPipeline.loadBundle();
        ValidationReport validationReport = buildValidationReport(bundle);
        String reportPath = validationReport.reportPath();

        if (validationReport.errorCount() > 0) {
            MigrationRecord blocked = storage.createMigrationRecord(
                    "import",
                    "BLOCKED",
                    operatorName,
                    "Validation blocked import. errors=" + validationReport.errorCount() + " warnings=" + validationReport.warningCount(),
                    null,
                    null,
                    reportPath,
                    bundle.sourceHash()
            );
            storage.addAudit("import_blocked", operatorName, blocked.recordId(), blocked.summary());
            throw new IllegalStateException("Validation blocked import. record=" + blocked.recordId() + " report=" + reportPath);
        }

        long snapshotId = storage.createSnapshot("pre-import by " + operatorName + " at " + Instant.now());
        MigrationRecord record = storage.createMigrationRecord(
                "import",
                "RUNNING",
                operatorName,
                "Import started",
                snapshotId,
                null,
                reportPath,
                bundle.sourceHash()
        );

        String exportPath = writeJson(config.resolveExportsDir(plugin).resolve("import-" + record.recordId() + ".json"), bundle);
        storage.replaceAll(record.recordId(), new MigrationSnapshot(
                bundle.petDefinitions(),
                bundle.categoryDefinitions(),
                bundle.playerProfiles(),
                bundle.assets()
        ));
        String status = validationReport.warningCount() > 0 ? "SUCCESS_WITH_WARNINGS" : "SUCCESS";
        MigrationRecord completed = storage.updateMigrationRecord(
                record.recordId(),
                status,
                "Imported pets=" + bundle.petDefinitions().size()
                        + " categories=" + bundle.categoryDefinitions().size()
                        + " players=" + bundle.playerProfiles().size()
                        + " assets=" + bundle.assets().size(),
                exportPath,
                reportPath,
                bundle.sourceHash()
        );
        storage.addAudit("import_success", operatorName, completed.recordId(), completed.summary());
        runtimeManager.refreshOnlinePlayers("import-" + completed.recordId());
        return new LegacyImportResult(
                completed,
                bundle.petDefinitions().size(),
                bundle.categoryDefinitions().size(),
                bundle.playerProfiles().size(),
                bundle.assets().size(),
                (int) validationReport.errorCount(),
                (int) validationReport.warningCount()
        );
    }

    public ValidationReport runValidation() throws Exception {
        return buildValidationReport(legacyImportPipeline.loadBundle());
    }

    public RollbackResult runRollback(String target, String operatorName) throws Exception {
        ResolvedSnapshot resolvedSnapshot = resolveRollbackSnapshot(target);
        MigrationRecord record = storage.createMigrationRecord(
                "rollback",
                "RUNNING",
                operatorName,
                "Rollback started from snapshot " + resolvedSnapshot.snapshotId(),
                resolvedSnapshot.snapshotId(),
                null,
                null,
                resolvedSnapshot.sourceHash()
        );
        MigrationSnapshot snapshot = storage.restoreSnapshot(resolvedSnapshot.snapshotId(), record.recordId());
        MigrationRecord completed = storage.updateMigrationRecord(
                record.recordId(),
                "SUCCESS",
                "Rollback restored pets=" + snapshot.petDefinitions().size()
                        + " categories=" + snapshot.categoryDefinitions().size()
                        + " players=" + snapshot.playerProfiles().size(),
                null,
                null,
                resolvedSnapshot.sourceHash()
        );
        storage.addAudit("rollback_success", operatorName, completed.recordId(), completed.summary());
        runtimeManager.refreshOnlinePlayers("rollback-" + completed.recordId());
        return new RollbackResult(
                completed,
                resolvedSnapshot.snapshotId(),
                snapshot.petDefinitions().size(),
                snapshot.categoryDefinitions().size(),
                snapshot.playerProfiles().size(),
                "Rollback completed."
        );
    }

    public GiveResult givePet(UUID playerUuid, String playerName, String requestedPetId, String operatorName) throws Exception {
        String petId = resolvePetId(requestedPetId);
        if (petId == null) {
            throw new IllegalArgumentException("Unknown pet id: " + requestedPetId);
        }

        Optional<PlayerPetProfile> existingProfile = storage.findPlayerProfile(playerUuid);
        Set<String> ownedPetIds = new LinkedHashSet<>();
        String activePetId = null;
        PlayerPetProfile base = null;
        if (existingProfile.isPresent()) {
            base = existingProfile.get();
            ownedPetIds.addAll(base.ownedPetIds());
            activePetId = base.activePetId();
        }
        boolean added = ownedPetIds.add(petId);
        boolean activeAssigned = false;
        if (activePetId == null || activePetId.isBlank()) {
            activePetId = petId;
            activeAssigned = true;
        }
        String rawPayload = gson.toJson(ownedPetIds);
        PlayerPetProfile updated = new PlayerPetProfile(
                playerUuid,
                playerName == null ? "" : playerName,
                ownedPetIds,
                activePetId,
                base == null ? java.util.Map.of() : base.petStatsByPetId(),
                base == null ? java.util.Map.of() : base.customNamesByPetId(),
                base == null ? java.util.Map.of() : base.inventoriesByPetId(),
                base == null ? List.of() : base.rawPetStats(),
                base == null ? "manual" : base.legacySourceType(),
                base == null ? "manual" : base.sourcePath(),
                rawPayload,
                HashUtil.sha256(rawPayload)
        );

        MigrationRecord record = storage.createMigrationRecord(
                "give",
                "RUNNING",
                operatorName,
                "Granting pet " + petId + " to " + playerUuid,
                null,
                null,
                null,
                updated.contentHash()
        );
        storage.upsertPlayerProfile(updated, record.recordId());
        MigrationRecord completed = storage.updateMigrationRecord(
                record.recordId(),
                "SUCCESS",
                (added ? "Granted " : "Already owned ") + petId + " for " + playerUuid,
                null,
                null,
                updated.contentHash()
        );
        storage.addAudit("give_pet", operatorName, completed.recordId(), completed.summary());
        runtimeManager.queueRefreshOnlinePlayer(playerUuid, "give-" + completed.recordId());
        return new GiveResult(
                playerUuid,
                updated.playerName(),
                petId,
                activeAssigned,
                updated.ownedPetIds().size(),
                added ? "Pet granted." : "Player already owned pet."
        );
    }

    public List<String> debugMount(UUID playerUuid, String input) {
        UUID targetUuid = runtimeManager.resolveDebugEntity(playerUuid);
        List<String> lines = new ArrayList<>(betterModelAdapter.debugMount(targetUuid, input));
        appendProfileContext(lines, playerUuid);
        lines.addAll(runtimeManager.debugSession(playerUuid));
        return lines;
    }

    public List<String> debugTracker(UUID playerUuid, String input) {
        UUID targetUuid = runtimeManager.resolveDebugEntity(playerUuid);
        List<String> lines = new ArrayList<>(betterModelAdapter.debugTracker(targetUuid, input));
        appendProfileContext(lines, playerUuid);
        lines.addAll(runtimeManager.debugSession(playerUuid));
        return lines;
    }

    public List<ValidationIssue> sampleIssues(ValidationReport report) {
        int limit = config.validation().sampleIssueLimit();
        return report.issues().stream().limit(limit).toList();
    }

    private void appendProfileContext(List<String> lines, UUID playerUuid) {
        try {
            Optional<PlayerPetProfile> profile = storage.findPlayerProfile(playerUuid);
            if (profile.isEmpty()) {
                lines.add("[BonfirePets] No stored BonfirePets profile for " + playerUuid);
                return;
            }
            PlayerPetProfile value = profile.get();
            lines.add("[BonfirePets] profile active=" + value.activePetId() + " owned=" + value.ownedPetIds().size() + " source=" + value.legacySourceType());
        } catch (Exception exception) {
            lines.add("[BonfirePets] profile debug failed: " + exception.getMessage());
        }
    }

    private ValidationReport buildValidationReport(LegacyImportBundle bundle) throws IOException {
        BetterModelProbe betterModelProbe = betterModelAdapter.probe();
        List<ValidationIssue> issues = new ArrayList<>(bundle.issues());
        extendWithBetterModelChecks(bundle.petDefinitions(), betterModelProbe, issues);
        extendWithMythicChecks(bundle, issues);
        String reportPath = config.validation().writeReportFile()
                ? writeJson(config.resolveReportsDir(plugin).resolve("validation-" + timestamp() + ".json"),
                new ValidationReport(
                        Instant.now(),
                        bundle.petDefinitions().size(),
                        bundle.categoryDefinitions().size(),
                        bundle.playerProfiles().size(),
                        betterModelProbe,
                        bundle.mythicMobsScanSummary(),
                        issues,
                        null
                ))
                : "";
        return new ValidationReport(
                Instant.now(),
                bundle.petDefinitions().size(),
                bundle.categoryDefinitions().size(),
                bundle.playerProfiles().size(),
                betterModelProbe,
                bundle.mythicMobsScanSummary(),
                issues,
                reportPath
        );
    }

    private void extendWithBetterModelChecks(List<PetDefinition> pets, BetterModelProbe probe, List<ValidationIssue> issues) {
        if (config.betterModel().requirePluginForImportValidation() && !probe.pluginPresent()) {
            issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, "bettermodel-missing", "BetterModel plugin is required by config but not present.", "BetterModel"));
        }
        if (!config.validation().strictModelCheck()) {
            return;
        }
        if (!probe.pluginPresent()) {
            issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, "bettermodel-probe-missing", "Strict BetterModel validation is enabled but BetterModel is not loaded.", "BetterModel"));
            return;
        }
        for (PetDefinition pet : pets) {
            if (!betterModelAdapter.modelExists(pet.renderer().betterModelId())) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, "bettermodel-model-missing",
                        "BetterModel model not found: " + pet.renderer().betterModelId(), pet.petId()));
            }
        }
    }

    private void extendWithMythicChecks(LegacyImportBundle bundle, List<ValidationIssue> issues) {
        int meg = bundle.mythicMobsScanSummary().occurrenceCounts().getOrDefault("skill.meg", 0);
        int passengers = bundle.mythicMobsScanSummary().occurrenceCounts().getOrDefault("modelpassengers", 0);
        int lockHead = bundle.mythicMobsScanSummary().occurrenceCounts().getOrDefault("lockmodelhead", 0);
        ValidationIssue.Severity strictSeverity = config.validation().strictMythicMobsCheck()
                ? ValidationIssue.Severity.ERROR
                : ValidationIssue.Severity.WARNING;
        if (meg > 0) {
            issues.add(new ValidationIssue(strictSeverity, "legacy-meg-skill", "Found " + meg + " legacy skill.meg usages that must move into Java/BetterModel logic.", "MythicMobs"));
        }
        if (passengers > 0) {
            issues.add(new ValidationIssue(strictSeverity, "legacy-modelpassengers", "Found " + passengers + " @modelpassengers usages that need replacement.", "MythicMobs"));
        }
        if (lockHead > 0) {
            issues.add(new ValidationIssue(strictSeverity, "legacy-lockmodelhead", "Found " + lockHead + " lockmodelhead usages that need replacement.", "MythicMobs"));
        }
        int state = bundle.mythicMobsScanSummary().occurrenceCounts().getOrDefault("state", 0);
        int defaultstate = bundle.mythicMobsScanSummary().occurrenceCounts().getOrDefault("defaultstate", 0);
        if (state > 0 || defaultstate > 0) {
            issues.add(new ValidationIssue(ValidationIssue.Severity.INFO, "legacy-state-usage",
                    "Detected state/defaultstate usage. Validate these against BetterModel MythicMobs compatibility during conversion.",
                    "MythicMobs"));
        }
    }

    private ResolvedSnapshot resolveRollbackSnapshot(String target) throws Exception {
        if (target == null || target.isBlank() || "latest".equalsIgnoreCase(target)) {
            MigrationRecord latestImport = storage.getLatestMigrationRecord("import")
                    .orElseThrow(() -> new IllegalStateException("No import record available for rollback."));
            if (latestImport.rollbackSnapshotId() == null) {
                throw new IllegalStateException("Latest import record has no rollback snapshot.");
            }
            return new ResolvedSnapshot(latestImport.rollbackSnapshotId(), latestImport.sourceHash());
        }

        Optional<MigrationRecord> record = storage.getMigrationRecord(target);
        if (record.isPresent()) {
            MigrationRecord value = record.get();
            if (value.rollbackSnapshotId() == null) {
                throw new IllegalStateException("Migration record has no rollback snapshot: " + target);
            }
            return new ResolvedSnapshot(value.rollbackSnapshotId(), value.sourceHash());
        }

        long snapshotId = Long.parseLong(target);
        return new ResolvedSnapshot(snapshotId, "");
    }

    private String resolvePetId(String requestedPetId) throws Exception {
        if (storage.petExists(requestedPetId)) {
            return requestedPetId;
        }
        String normalized = requestedPetId.toLowerCase(Locale.ROOT);
        return storage.listPetIds().stream()
                .filter(petId -> petId.equalsIgnoreCase(normalized) || petId.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private String writeJson(Path path, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, gson.toJson(payload), StandardCharsets.UTF_8);
        return path.toString();
    }

    private String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withLocale(Locale.ROOT).format(java.time.ZonedDateTime.now());
    }

    @Override
    public void close() {
        runtimeManager.close();
        storage.close();
    }

    private record ResolvedSnapshot(long snapshotId, String sourceHash) {
    }
}
