package com.bonfire.pets.service;

import com.bonfire.pets.model.PetDefinitionCatalog;
import com.bonfire.pets.model.RollbackRiskReport;
import com.bonfire.pets.model.SnapshotRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MCPetsSnapshotRiskInspector {

    private static final Pattern SEMICOLON_ID_PATTERN = Pattern.compile("([A-Za-z0-9_.:-]+)\s*;");
    private static final Pattern JSON_PET_ID_PATTERN = Pattern.compile("\"petId\"\s*:\s*\"([A-Za-z0-9_.:-]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE64_TOKEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9+/=])([A-Za-z0-9+/=]{16,})(?![A-Za-z0-9+/=])");

    public RollbackRiskReport inspect(SnapshotRecord snapshot, PetDefinitionCatalog catalog) {
        Set<String> referencedPetIds = new TreeSet<>();
        collectSemicolonIds(snapshot.namesRaw(), referencedPetIds);
        collectSemicolonIds(snapshot.inventoriesRaw(), referencedPetIds);
        collectJsonPetIds(snapshot.dataRaw(), referencedPetIds);

        if (referencedPetIds.isEmpty()) {
            if (hasPayload(snapshot)) {
                return new RollbackRiskReport(false, List.of(), List.of(),
                        "Unable to infer pet ids from snapshot payload. Rollback is blocked to avoid writing incompatible MCPets data.");
            }
            return new RollbackRiskReport(true, List.of(), List.of(), "No pet references detected in snapshot payload.");
        }

        List<String> referenced = new ArrayList<>(referencedPetIds);
        if (!catalog.available()) {
            return new RollbackRiskReport(false, referenced, List.of(),
                    "MCPets pet definition index unavailable: " + catalog.reason());
        }

        List<String> missing = referenced.stream()
                .filter(petId -> !catalog.contains(petId))
                .toList();
        if (!missing.isEmpty()) {
            return new RollbackRiskReport(false, referenced, missing,
                    "Current MCPets pet definitions are missing: " + String.join(", ", missing));
        }

        return new RollbackRiskReport(true, referenced, List.of(),
                "Validated pet definitions: " + String.join(", ", referenced));
    }

    private void collectSemicolonIds(String raw, Set<String> target) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        Matcher matcher = SEMICOLON_ID_PATTERN.matcher(raw);
        while (matcher.find()) {
            target.add(normalize(matcher.group(1)));
        }
    }

    private void collectJsonPetIds(String raw, Set<String> target) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        collectJsonPetIdsFromText(raw, target);

        Set<String> seenTokens = new LinkedHashSet<>();
        Matcher matcher = BASE64_TOKEN_PATTERN.matcher(raw);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!seenTokens.add(token)) {
                continue;
            }

            try {
                String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
                collectJsonPetIdsFromText(decoded, target);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void collectJsonPetIdsFromText(String text, Set<String> target) {
        Matcher matcher = JSON_PET_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            target.add(normalize(matcher.group(1)));
        }
    }

    private boolean hasPayload(SnapshotRecord snapshot) {
        return hasText(snapshot.namesRaw()) || hasText(snapshot.inventoriesRaw()) || hasText(snapshot.dataRaw());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
