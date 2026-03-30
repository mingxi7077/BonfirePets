package com.bonfire.pets.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record PetDefinitionCatalog(
        boolean available,
        String petsDirectory,
        int petCount,
        Set<String> petIds,
        String reason,
        Instant scannedAt
) {

    public PetDefinitionCatalog {
        petsDirectory = petsDirectory == null ? "" : petsDirectory;
        petIds = petIds == null ? Set.of() : Set.copyOf(petIds);
        reason = reason == null ? "" : reason;
        scannedAt = scannedAt == null ? Instant.now() : scannedAt;
    }

    public static PetDefinitionCatalog available(String petsDirectory, Set<String> petIds, Instant scannedAt) {
        return new PetDefinitionCatalog(true, petsDirectory, petIds == null ? 0 : petIds.size(), petIds, "ok", scannedAt);
    }

    public static PetDefinitionCatalog unavailable(String petsDirectory, String reason, Instant scannedAt) {
        return new PetDefinitionCatalog(false, petsDirectory, 0, Set.of(), reason, scannedAt);
    }

    public boolean contains(String petId) {
        return petIds.contains(normalize(petId));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
