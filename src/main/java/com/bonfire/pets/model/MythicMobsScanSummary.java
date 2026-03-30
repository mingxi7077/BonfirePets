package com.bonfire.pets.model;

import java.util.List;
import java.util.Map;

public record MythicMobsScanSummary(
        Map<String, Integer> occurrenceCounts,
        List<String> sampleFiles
) {

    public MythicMobsScanSummary {
        occurrenceCounts = occurrenceCounts == null ? Map.of() : Map.copyOf(occurrenceCounts);
        sampleFiles = sampleFiles == null ? List.of() : List.copyOf(sampleFiles);
    }
}
