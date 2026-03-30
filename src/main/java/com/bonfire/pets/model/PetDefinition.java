package com.bonfire.pets.model;

import java.util.List;

public record PetDefinition(
        String petId,
        String legacyId,
        String displayName,
        String permissionNode,
        List<String> categoryIds,
        RendererSpec renderer,
        BehaviorSpec behavior,
        MountSpec mount,
        List<SignalSpec> signals,
        String sourcePath,
        String rawYaml,
        String contentHash
) {

    public PetDefinition {
        categoryIds = categoryIds == null ? List.of() : List.copyOf(categoryIds);
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public boolean hasSignals() {
        return !signals.isEmpty();
    }
}
