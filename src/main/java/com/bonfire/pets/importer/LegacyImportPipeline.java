package com.bonfire.pets.importer;

import com.bonfire.pets.config.BonfirePetsConfig;
import com.bonfire.pets.model.BehaviorSpec;
import com.bonfire.pets.model.CategoryDefinition;
import com.bonfire.pets.model.LegacyAssetBlob;
import com.bonfire.pets.model.LegacyImportBundle;
import com.bonfire.pets.model.MountSpec;
import com.bonfire.pets.model.MythicMobsScanSummary;
import com.bonfire.pets.model.PetDefinition;
import com.bonfire.pets.model.PlayerPetProfile;
import com.bonfire.pets.model.RendererSpec;
import com.bonfire.pets.model.SignalSpec;
import com.bonfire.pets.model.ValidationIssue;
import com.bonfire.pets.util.HashUtil;
import com.bonfire.pets.validation.MythicMobsAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class LegacyImportPipeline {

    private static final Pattern BASE64_TOKEN_PATTERN = Pattern.compile("([A-Za-z0-9+/=]{16,})");
    private static final Pattern PET_ID_PATTERN = Pattern.compile("\"petId\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final JavaPlugin plugin;
    private final BonfirePetsConfig config;
    private final MythicMobsAdapter mythicMobsAdapter;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public LegacyImportPipeline(JavaPlugin plugin, BonfirePetsConfig config, MythicMobsAdapter mythicMobsAdapter) {
        this.plugin = plugin;
        this.config = config;
        this.mythicMobsAdapter = mythicMobsAdapter;
    }

    public LegacyImportBundle loadBundle() {
        List<ValidationIssue> issues = new ArrayList<>();
        LegacyGlobalSettings globalSettings = loadGlobalSettings(issues);
        List<CategoryDefinition> categoryDefinitions = loadCategoryDefinitions(issues);
        Map<String, List<String>> petToCategories = buildCategoryMembership(categoryDefinitions);
        List<PetDefinition> petDefinitions = loadPetDefinitions(petToCategories, globalSettings, issues);
        Map<UUID, PlayerPetProfile> profiles = new TreeMap<>((left, right) -> left.toString().compareToIgnoreCase(right.toString()));
        loadFileProfiles(profiles, issues);
        mergeMysqlProfiles(profiles, issues);
        List<PlayerPetProfile> playerProfiles = new ArrayList<>(profiles.values());
        List<LegacyAssetBlob> assets = loadAssets(issues);
        postValidate(categoryDefinitions, petDefinitions, playerProfiles, issues);
        MythicMobsScanSummary mythicScan = mythicMobsAdapter.scan(config.resolveLegacyMythicMobsDir());
        String sourceHash = hashAll(petDefinitions, categoryDefinitions, playerProfiles, assets);
        return new LegacyImportBundle(petDefinitions, categoryDefinitions, playerProfiles, assets, mythicScan, issues, sourceHash);
    }

    private LegacyGlobalSettings loadGlobalSettings(List<ValidationIssue> issues) {
        Path configPath = config.resolveLegacyMcpetsConfig();
        if (!Files.exists(configPath)) {
            issues.add(issue(ValidationIssue.Severity.ERROR, "legacy-config-missing", "Missing legacy MCPets config.", configPath.toString()));
            return new LegacyGlobalSettings(true, false, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configPath.toFile());
        return new LegacyGlobalSettings(
                yaml.getBoolean("Nameable", true),
                yaml.getBoolean("SpawnPetOnReconnect", false),
                yaml.getBoolean("DismountOnDamaged", false)
        );
    }

    private List<CategoryDefinition> loadCategoryDefinitions(List<ValidationIssue> issues) {
        Path categoriesDir = config.resolveLegacyMcpetsDir().resolve("Categories");
        if (!Files.isDirectory(categoriesDir)) {
            issues.add(issue(ValidationIssue.Severity.ERROR, "categories-missing", "Legacy MCPets categories directory not found.", categoriesDir.toString()));
            return List.of();
        }

        List<CategoryDefinition> categories = new ArrayList<>();
        for (Path path : listYamlFiles(categoriesDir)) {
            try {
                String rawYaml = readText(path);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
                String categoryId = valueOrDefault(yaml.getString("Id"), stem(path));
                String displayName = valueOrDefault(yaml.getString("DisplayName"), categoryId);
                categories.add(new CategoryDefinition(
                        categoryId,
                        categoryId,
                        displayName,
                        yaml.getBoolean("DefaultCategory", false),
                        yaml.getStringList("Pets"),
                        yaml.getStringList("ExcludedCategories"),
                        valueOrDefault(yaml.getString("IconName"), displayName),
                        sectionToJson(yaml, "Icon"),
                        path.toString(),
                        HashUtil.sha256(rawYaml)
                ));
            } catch (Exception exception) {
                issues.add(issue(ValidationIssue.Severity.ERROR, "category-load-failed", exception.getMessage(), path.toString()));
            }
        }
        return categories;
    }

    private Map<String, List<String>> buildCategoryMembership(List<CategoryDefinition> categories) {
        Map<String, List<String>> membership = new LinkedHashMap<>();
        for (CategoryDefinition category : categories) {
            for (String petId : category.petIds()) {
                membership.computeIfAbsent(normalizeKey(petId), ignored -> new ArrayList<>()).add(category.categoryId());
            }
        }
        return membership;
    }

    private List<PetDefinition> loadPetDefinitions(Map<String, List<String>> petToCategories,
                                                   LegacyGlobalSettings globalSettings,
                                                   List<ValidationIssue> issues) {
        Path petsDir = config.resolveLegacyMcpetsDir().resolve("Pets");
        if (!Files.isDirectory(petsDir)) {
            issues.add(issue(ValidationIssue.Severity.ERROR, "pets-missing", "Legacy MCPets pets directory not found.", petsDir.toString()));
            return List.of();
        }

        List<PetDefinition> pets = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Path path : listYamlFiles(petsDir)) {
            try {
                String rawYaml = readText(path);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
                String legacyId = valueOrDefault(yaml.getString("Id"), stem(path));
                String key = normalizeKey(legacyId);
                if (!seenIds.add(key)) {
                    issues.add(issue(ValidationIssue.Severity.ERROR, "duplicate-pet-id", "Duplicate legacy pet id " + legacyId, path.toString()));
                    continue;
                }
                String mythicMob = valueOrDefault(yaml.getString("MythicMob"), legacyId);
                String betterModelId = firstNonBlank(yaml.getString("BetterModel"), yaml.getString("Model"), yaml.getString("ModelId"), mythicMob);
                String displayName = firstNonBlank(yaml.getString("DisplayName"), yaml.getString("Icon.DisplayName"), yaml.getString("Icon.Name"), legacyId);
                boolean mountable = yaml.getBoolean("Mountable", false);
                String mountType = valueOrDefault(yaml.getString("MountType"), mountable ? "walking" : "");
                List<String> categories = petToCategories.getOrDefault(key, List.of());
                if (categories.isEmpty()) {
                    issues.add(issue(ValidationIssue.Severity.WARNING, "pet-without-category", "Pet is not referenced by any category.", legacyId));
                }
                pets.add(new PetDefinition(
                        legacyId,
                        legacyId,
                        displayName,
                        valueOrDefault(yaml.getString("Permission"), yaml.getString("MountPermission")),
                        categories,
                        new RendererSpec(
                                mythicMob,
                                betterModelId,
                                firstNonBlank(yaml.getString("NameBone"), yaml.getString("NameTagBone")),
                                firstNonBlank(yaml.getString("Icon.Material"), yaml.getString("Icon.id")),
                                integerOrNull(yaml.get("Icon.CustomModelData")),
                                firstNonBlank(yaml.getString("Icon.TextureBase64"), yaml.getString("Icon.texture")),
                                firstNonBlank(yaml.getString("Icon.Name"), yaml.getString("Icon.DisplayName"), legacyId),
                                firstNonBlankList(yaml.getStringList("Icon.Description"), yaml.getStringList("Icon.Lore")),
                                sectionToJson(yaml, "Icon"),
                                sectionToJson(yaml, "Skins")
                        ),
                        new BehaviorSpec(
                                yaml.getString("DespawnSkill"),
                                integerOrNull(yaml.get("InventorySize")),
                                integerOrNull(yaml.get("Distance")),
                                integerOrNull(yaml.get("SpawnRange")),
                                integerOrNull(yaml.get("ComingBackRange")),
                                yaml.getBoolean("AutoRide", false),
                                yaml.getBoolean("DespawnOnDismount", false),
                                yaml.isSet("DismountOnDamaged") ? yaml.getBoolean("DismountOnDamaged") : globalSettings.dismountOnDamaged(),
                                globalSettings.nameable(),
                                globalSettings.spawnPetOnReconnect(),
                                List.of(),
                                rawYaml
                        ),
                        new MountSpec(
                                mountable,
                                mountType,
                                "flying".equalsIgnoreCase(mountType),
                                mountable,
                                mountable && !"flying".equalsIgnoreCase(mountType),
                                true,
                                yaml.isSet("DismountOnDamaged") ? yaml.getBoolean("DismountOnDamaged") : globalSettings.dismountOnDamaged(),
                                firstNonBlank(yaml.getString("MountPermission"), yaml.getString("Permission"))
                        ),
                        parseSignals(legacyId, yaml),
                        path.toString(),
                        rawYaml,
                        HashUtil.sha256(rawYaml)
                ));
            } catch (Exception exception) {
                issues.add(issue(ValidationIssue.Severity.ERROR, "pet-load-failed", exception.getMessage(), path.toString()));
            }
        }
        return pets;
    }

    private List<SignalSpec> parseSignals(String petId, YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("Signals");
        if (section == null) {
            return List.of();
        }
        return List.of(new SignalSpec(
                petId + ":signal",
                section.getStringList("Values"),
                section.getString("Item.Name"),
                section.getString("Item.Material"),
                integerOrNull(section.get("Item.CustomModelData")),
                toJsonSafe(section.getValues(true))
        ));
    }

    private void loadFileProfiles(Map<UUID, PlayerPetProfile> profiles, List<ValidationIssue> issues) {
        Path playerDataDir = config.resolveLegacyPlayerDataDir();
        if (!Files.isDirectory(playerDataDir)) {
            issues.add(issue(ValidationIssue.Severity.WARNING, "playerdata-missing", "Legacy MCPets PlayerData directory not found.", playerDataDir.toString()));
            return;
        }
        for (Path path : listYamlFiles(playerDataDir)) {
            try {
                UUID playerUuid = UUID.fromString(stem(path));
                String rawYaml = readText(path);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
                Map<String, String> names = parseSemicolonEntries(yaml.getStringList("Names"));
                Map<String, String> inventories = parseSemicolonEntries(yaml.getStringList("Inventories"));
                Map<String, String> petStats = parsePetStats(yaml.getStringList("PetStats"));
                Set<String> ownedPetIds = collectOwnedPetIds(names, inventories, petStats);
                PlayerPetProfile profile = new PlayerPetProfile(
                        playerUuid,
                        resolvePlayerName(playerUuid),
                        ownedPetIds,
                        inferActivePetId(ownedPetIds, names, inventories),
                        petStats,
                        names,
                        inventories,
                        yaml.getStringList("PetStats"),
                        "file",
                        path.toString(),
                        rawYaml,
                        HashUtil.sha256(rawYaml)
                );
                profiles.put(playerUuid, profile);
            } catch (Exception exception) {
                issues.add(issue(ValidationIssue.Severity.WARNING, "playerdata-load-failed", exception.getMessage(), path.toString()));
            }
        }
    }

    private void mergeMysqlProfiles(Map<UUID, PlayerPetProfile> profiles, List<ValidationIssue> issues) {
        if (!config.legacy().importMysqlWhenAvailable()) {
            return;
        }
        MysqlSourceConfig mysql = resolveMysqlConfig(issues);
        if (mysql == null) {
            return;
        }
        String sql = "SELECT uuid, names, inventories, data FROM " + mysql.tableName();
        int count = 0;
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl(), mysql.user(), mysql.password());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.legacy().mysqlConnectTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                UUID playerUuid = UUID.fromString(resultSet.getString("uuid"));
                PlayerPetProfile mysqlProfile = parseMysqlProfile(
                        playerUuid,
                        resultSet.getString("names"),
                        resultSet.getString("inventories"),
                        resultSet.getString("data")
                );
                profiles.merge(playerUuid, mysqlProfile, this::mergeProfiles);
                count++;
            }
            issues.add(issue(ValidationIssue.Severity.INFO, "mysql-profiles-loaded", "Loaded " + count + " legacy MySQL rows.", mysql.tableName()));
            }
        } catch (Exception exception) {
            issues.add(issue(ValidationIssue.Severity.WARNING, "mysql-profiles-skipped", exception.getMessage(), mysql.tableName()));
        }
    }

    private MysqlSourceConfig resolveMysqlConfig(List<ValidationIssue> issues) {
        Path configPath = config.resolveLegacyMcpetsConfig();
        if (!Files.exists(configPath)) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configPath.toFile());
        if (yaml.getBoolean("DisableMySQL", false)) {
            return null;
        }
        String host = yaml.getString("MySQL.Host");
        String port = valueOrDefault(yaml.getString("MySQL.Port"), "3306");
        String database = yaml.getString("MySQL.Database");
        String user = yaml.getString("MySQL.User");
        String password = valueOrDefault(yaml.getString("MySQL.Password"), "");
        String prefix = valueOrDefault(yaml.getString("MySQL.Prefix"), "");
        String tableName = prefix + "mcpets_player_data";
        if (host == null || database == null || user == null) {
            issues.add(issue(ValidationIssue.Severity.WARNING, "mysql-config-incomplete", "Legacy MCPets MySQL config is incomplete.", configPath.toString()));
            return null;
        }
        return new MysqlSourceConfig(
                "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"
                        + "&connectTimeout=" + (config.legacy().mysqlConnectTimeoutSeconds() * 1000),
                user,
                password,
                tableName
        );
    }

    private PlayerPetProfile parseMysqlProfile(UUID playerUuid, String namesRaw, String inventoriesRaw, String dataRaw) {
        Map<String, String> names = parseSemicolonEntries(extractStrings(namesRaw));
        Map<String, String> inventories = parseSemicolonEntries(extractStrings(inventoriesRaw));
        List<String> rawPetStats = extractBase64Stats(dataRaw);
        Map<String, String> petStats = parsePetStats(rawPetStats);
        Set<String> ownedPetIds = collectOwnedPetIds(names, inventories, petStats);
        String rawSynthetic = gson.toJson(Map.of(
                "names", valueOrDefault(namesRaw, ""),
                "inventories", valueOrDefault(inventoriesRaw, ""),
                "data", valueOrDefault(dataRaw, "")
        ));
        return new PlayerPetProfile(
                playerUuid,
                resolvePlayerName(playerUuid),
                ownedPetIds,
                inferActivePetId(ownedPetIds, names, inventories),
                petStats,
                names,
                inventories,
                rawPetStats,
                "mysql",
                config.resolveLegacyMcpetsConfig().toString(),
                rawSynthetic,
                HashUtil.sha256(rawSynthetic)
        );
    }

    private PlayerPetProfile mergeProfiles(PlayerPetProfile left, PlayerPetProfile right) {
        Set<String> owned = new LinkedHashSet<>(left.ownedPetIds());
        owned.addAll(right.ownedPetIds());

        Map<String, String> petStats = new LinkedHashMap<>(left.petStatsByPetId());
        petStats.putAll(right.petStatsByPetId());

        Map<String, String> names = new LinkedHashMap<>(left.customNamesByPetId());
        names.putAll(right.customNamesByPetId());

        Map<String, String> inventories = new LinkedHashMap<>(left.inventoriesByPetId());
        inventories.putAll(right.inventoriesByPetId());

        List<String> rawPetStats = new ArrayList<>(left.rawPetStats());
        rawPetStats.addAll(right.rawPetStats());

        String activePetId = firstNonBlank(left.activePetId(), right.activePetId(), inferActivePetId(owned, names, inventories));
        String playerName = firstNonBlank(left.playerName(), right.playerName());
        String raw = left.rawYaml() + System.lineSeparator() + right.rawYaml();
        return new PlayerPetProfile(
                left.playerUuid(),
                playerName,
                owned,
                activePetId,
                petStats,
                names,
                inventories,
                rawPetStats,
                left.legacySourceType() + "+" + right.legacySourceType(),
                left.sourcePath() + ";" + right.sourcePath(),
                raw,
                HashUtil.sha256(raw)
        );
    }

    private List<LegacyAssetBlob> loadAssets(List<ValidationIssue> issues) {
        List<LegacyAssetBlob> assets = new ArrayList<>();
        addAsset(assets, issues, "mcpets-config", config.resolveLegacyMcpetsConfig());
        addAsset(assets, issues, "menu-icons", config.resolveLegacyMenuIcons());
        addAsset(assets, issues, "pet-foods", config.resolveLegacyPetFoods());
        return assets;
    }

    private void addAsset(List<LegacyAssetBlob> assets, List<ValidationIssue> issues, String assetKey, Path path) {
        if (!Files.exists(path)) {
            issues.add(issue(ValidationIssue.Severity.WARNING, "asset-missing", "Legacy asset file missing.", path.toString()));
            return;
        }
        try {
            String raw = readText(path);
            assets.add(new LegacyAssetBlob(assetKey, path.toString(), raw, HashUtil.sha256(raw)));
        } catch (Exception exception) {
            issues.add(issue(ValidationIssue.Severity.WARNING, "asset-load-failed", exception.getMessage(), path.toString()));
        }
    }

    private void postValidate(List<CategoryDefinition> categories,
                              List<PetDefinition> pets,
                              List<PlayerPetProfile> profiles,
                              List<ValidationIssue> issues) {
        Set<String> petIds = pets.stream().map(PetDefinition::petId).map(this::normalizeKey).collect(Collectors.toCollection(LinkedHashSet::new));
        for (CategoryDefinition category : categories) {
            for (String petId : category.petIds()) {
                if (!petIds.contains(normalizeKey(petId))) {
                    issues.add(issue(ValidationIssue.Severity.WARNING, "category-missing-pet", "Category references missing pet " + petId, category.categoryId()));
                }
            }
        }
        for (PetDefinition pet : pets) {
            if (pet.renderer().mythicMobId() == null || pet.renderer().mythicMobId().isBlank()) {
                issues.add(issue(ValidationIssue.Severity.ERROR, "pet-missing-mythicmob", "Pet missing MythicMob mapping.", pet.petId()));
            }
            if (pet.mount().mountable() && (pet.mount().mountType() == null || pet.mount().mountType().isBlank())) {
                issues.add(issue(ValidationIssue.Severity.ERROR, "mount-missing-type", "Mountable pet missing MountType.", pet.petId()));
            }
        }
        for (PlayerPetProfile profile : profiles) {
            for (String ownedPetId : profile.ownedPetIds()) {
                if (!petIds.contains(normalizeKey(ownedPetId))) {
                    issues.add(issue(ValidationIssue.Severity.WARNING, "player-missing-pet", "Player owns pet not present in imported definitions: " + ownedPetId, profile.playerUuid().toString()));
                }
            }
        }
    }

    private Map<String, String> parseSemicolonEntries(Collection<String> entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int split = entry.indexOf(';');
            if (split <= 0) {
                result.putIfAbsent(entry.trim(), "");
                continue;
            }
            String petId = entry.substring(0, split).trim();
            String value = entry.substring(split + 1).trim();
            if (!petId.isBlank()) {
                result.put(petId, value);
            }
        }
        return result;
    }

    private Map<String, String> parsePetStats(Collection<String> rawPetStats) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String raw : rawPetStats) {
            String decoded = decodeBase64(raw);
            if (decoded == null || decoded.isBlank()) {
                continue;
            }
            String petId = extractPetId(decoded);
            if (petId != null) {
                result.put(petId, decoded);
            }
        }
        return result;
    }

    private List<String> extractStrings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                return gson.fromJson(trimmed, new TypeToken<List<String>>() {
                }.getType());
            } catch (Exception ignored) {
            }
        }
        return Stream.of(trimmed.split("\\r?\\n"))
                .map(String::trim)
                .map(value -> value.endsWith(",") ? value.substring(0, value.length() - 1) : value)
                .map(value -> stripQuotes(value))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> extractBase64Stats(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = BASE64_TOKEN_PATTERN.matcher(raw);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (extractPetId(decodeBase64(token)) != null) {
                values.add(token);
            }
        }
        return values;
    }

    private Set<String> collectOwnedPetIds(Map<String, String> names, Map<String, String> inventories, Map<String, String> petStats) {
        Set<String> ownedPetIds = new LinkedHashSet<>();
        ownedPetIds.addAll(names.keySet());
        ownedPetIds.addAll(inventories.keySet());
        ownedPetIds.addAll(petStats.keySet());
        return ownedPetIds;
    }

    private String inferActivePetId(Set<String> ownedPetIds, Map<String, String> names, Map<String, String> inventories) {
        if (ownedPetIds.size() == 1) {
            return ownedPetIds.iterator().next();
        }
        if (inventories.size() == 1) {
            return inventories.keySet().iterator().next();
        }
        if (names.size() == 1) {
            return names.keySet().iterator().next();
        }
        return null;
    }

    private String resolvePlayerName(UUID playerUuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        return player.getName() == null ? "" : player.getName();
    }

    private Integer integerOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> firstNonBlankList(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        return List.of();
    }

    private String sectionToJson(YamlConfiguration yaml, String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            Object value = yaml.get(path);
            return value == null ? "" : toJsonSafe(value);
        }
        return toJsonSafe(section.getValues(true));
    }

    private String toJsonSafe(Object value) {
        return gson.toJson(normalizeForJson(value, java.util.Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    // Flatten Bukkit/YAML values into JSON-safe structures so Gson avoids Java 21 reflective access traps.
    private Object normalizeForJson(Object value, Set<Object> visiting) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(entry -> normalizeForJson(entry, visiting)).orElse(null);
        }
        if (value instanceof UUID
                || value instanceof Date
                || value instanceof TimeZone
                || value instanceof TemporalAccessor
                || value instanceof Path
                || value instanceof Class<?>) {
            return value.toString();
        }
        if (value instanceof ConfigurationSection section) {
            if (!visiting.add(section)) {
                return "<cycle:" + value.getClass().getSimpleName() + ">";
            }
            try {
                return normalizeForJson(section.getValues(true), visiting);
            } finally {
                visiting.remove(section);
            }
        }
        if (value instanceof ConfigurationSerializable serializable) {
            if (!visiting.add(serializable)) {
                return "<cycle:" + value.getClass().getSimpleName() + ">";
            }
            try {
                return normalizeForJson(serializable.serialize(), visiting);
            } finally {
                visiting.remove(serializable);
            }
        }
        if (value.getClass().isArray()) {
            if (!visiting.add(value)) {
                return "<cycle:" + value.getClass().getSimpleName() + ">";
            }
            try {
                int length = Array.getLength(value);
                List<Object> normalized = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    normalized.add(normalizeForJson(Array.get(value, index), visiting));
                }
                return normalized;
            } finally {
                visiting.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            if (!visiting.add(value)) {
                return "<cycle:" + value.getClass().getSimpleName() + ">";
            }
            try {
                List<Object> normalized = new ArrayList<>();
                for (Object entry : iterable) {
                    normalized.add(normalizeForJson(entry, visiting));
                }
                return normalized;
            } finally {
                visiting.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (!visiting.add(value)) {
                return "<cycle:" + value.getClass().getSimpleName() + ">";
            }
            try {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), normalizeForJson(entry.getValue(), visiting));
                }
                return normalized;
            } finally {
                visiting.remove(value);
            }
        }
        return value.toString();
    }

    private ValidationIssue issue(ValidationIssue.Severity severity, String code, String message, String reference) {
        return new ValidationIssue(severity, code, message, reference);
    }

    private List<Path> listYamlFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private String readText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String extractPetId(String decodedJson) {
        if (decodedJson == null || decodedJson.isBlank()) {
            return null;
        }
        Matcher matcher = PET_ID_PATTERN.matcher(decodedJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String hashAll(List<PetDefinition> pets, List<CategoryDefinition> categories, List<PlayerPetProfile> profiles, List<LegacyAssetBlob> assets) {
        List<String> hashes = new ArrayList<>();
        pets.stream().map(PetDefinition::contentHash).forEach(hashes::add);
        categories.stream().map(CategoryDefinition::contentHash).forEach(hashes::add);
        profiles.stream().map(PlayerPetProfile::contentHash).forEach(hashes::add);
        assets.stream().map(LegacyAssetBlob::contentHash).forEach(hashes::add);
        hashes.sort(String::compareTo);
        return HashUtil.sha256(String.join("|", hashes));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String stem(Path path) {
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record LegacyGlobalSettings(
            boolean nameable,
            boolean spawnPetOnReconnect,
            boolean dismountOnDamaged
    ) {
    }

    private record MysqlSourceConfig(
            String jdbcUrl,
            String user,
            String password,
            String tableName
    ) {
    }
}
