package com.bonfire.pets.service;

import com.bonfire.pets.model.PetDefinitionCatalog;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class MCPetsPetDefinitionScanner {

    public PetDefinitionCatalog scan(Path mcpetsConfigPath) {
        Instant scannedAt = Instant.now();
        Path petsDirectory = mcpetsConfigPath == null || mcpetsConfigPath.getParent() == null
                ? null
                : mcpetsConfigPath.getParent().resolve("Pets").normalize();

        if (petsDirectory == null || !Files.isDirectory(petsDirectory)) {
            return PetDefinitionCatalog.unavailable(pathText(petsDirectory), "Pets directory not found", scannedAt);
        }

        Set<String> petIds = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(petsDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isYamlFile)
                    .forEach(path -> collectPetId(path, petIds));
        } catch (IOException exception) {
            return PetDefinitionCatalog.unavailable(pathText(petsDirectory), "Pets scan failed: " + exception.getMessage(), scannedAt);
        }

        if (petIds.isEmpty()) {
            return PetDefinitionCatalog.unavailable(pathText(petsDirectory), "No pet definitions were indexed", scannedAt);
        }
        return PetDefinitionCatalog.available(pathText(petsDirectory), petIds, scannedAt);
    }

    private boolean isYamlFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
    }

    private void collectPetId(Path path, Set<String> petIds) {
        String fileName = path.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex > 0) {
            petIds.add(normalize(fileName.substring(0, extensionIndex)));
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        String configuredId = yaml.getString("Id");
        if (configuredId != null && !configuredId.isBlank()) {
            petIds.add(normalize(configuredId));
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String pathText(Path path) {
        return path == null ? "" : path.toString();
    }
}
