package com.bonfire.pets.model;

import java.util.List;

public record BehaviorSpec(
        String despawnSkill,
        Integer inventorySize,
        Integer followDistance,
        Integer spawnRange,
        Integer returnRange,
        boolean autoRide,
        boolean despawnOnDismount,
        boolean dismountOnDamaged,
        boolean nameable,
        boolean spawnOnReconnect,
        List<String> legacyMechanics,
        String rawYaml
) {

    public BehaviorSpec {
        legacyMechanics = legacyMechanics == null ? List.of() : List.copyOf(legacyMechanics);
    }
}
