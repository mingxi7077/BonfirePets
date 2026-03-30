package com.bonfire.pets.model;

import java.util.List;

public record SignalSpec(
        String signalId,
        List<String> values,
        String itemName,
        String itemMaterial,
        Integer itemCustomModelData,
        String rawYaml
) {

    public SignalSpec {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
