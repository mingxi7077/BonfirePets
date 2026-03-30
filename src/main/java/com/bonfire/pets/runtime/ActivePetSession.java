package com.bonfire.pets.runtime;

import com.bonfire.pets.model.PetDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.time.Instant;
import java.util.UUID;

public record ActivePetSession(
        UUID ownerUuid,
        String ownerName,
        String petId,
        String mythicMobId,
        String betterModelId,
        UUID entityUuid,
        String worldName,
        String spawnReason,
        PetDefinition petDefinition,
        Instant startedAt
) {

    public Entity entity() {
        return entityUuid == null ? null : Bukkit.getEntity(entityUuid);
    }

    public boolean mountable() {
        return petDefinition != null && petDefinition.mount().mountable();
    }
}
