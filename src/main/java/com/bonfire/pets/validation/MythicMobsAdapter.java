package com.bonfire.pets.validation;

import com.bonfire.pets.model.MythicMobsScanSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class MythicMobsAdapter {

    private static final Map<String, String> PATTERNS = Map.of(
            "state", "state{",
            "defaultstate", "defaultstate{",
            "skill.meg", "skill.meg:",
            "modelpassengers", "@modelpassengers",
            "lockmodelhead", "lockmodelhead"
    );

    public MythicMobsScanSummary scan(Path mythicMobsDir) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        PATTERNS.keySet().forEach(key -> counts.put(key, 0));
        List<String> samples = new ArrayList<>();

        if (mythicMobsDir == null || !Files.isDirectory(mythicMobsDir)) {
            return new MythicMobsScanSummary(counts, List.of());
        }

        try (Stream<Path> stream = Files.walk(mythicMobsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isTextFile)
                    .forEach(path -> countFile(path, counts, samples));
        } catch (IOException ignored) {
        }
        return new MythicMobsScanSummary(counts, samples);
    }

    private void countFile(Path path, Map<String, Integer> counts, List<String> samples) {
        String lower;
        try {
            lower = Files.readString(path, StandardCharsets.UTF_8).toLowerCase();
        } catch (IOException exception) {
            return;
        }

        boolean sampled = false;
        for (Map.Entry<String, String> entry : PATTERNS.entrySet()) {
            int count = countOccurrences(lower, entry.getValue().toLowerCase());
            if (count > 0) {
                counts.compute(entry.getKey(), (key, value) -> value == null ? count : value + count);
                if (!sampled && samples.size() < 20) {
                    samples.add(path.toString());
                    sampled = true;
                }
            }
        }
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".txt");
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
