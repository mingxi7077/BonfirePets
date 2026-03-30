package com.bonfire.pets.model;

import java.util.List;

public record BetterModelProbe(
        boolean pluginPresent,
        String pluginVersion,
        boolean apiAvailable,
        boolean eventBusAvailable,
        int loadedModelCount,
        List<String> recentEvents,
        String message
) {

    public BetterModelProbe {
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
