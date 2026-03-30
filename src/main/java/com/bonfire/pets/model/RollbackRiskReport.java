package com.bonfire.pets.model;

import java.util.List;

public record RollbackRiskReport(
        boolean safe,
        List<String> referencedPetIds,
        List<String> missingPetIds,
        String reason
) {

    public RollbackRiskReport {
        referencedPetIds = referencedPetIds == null ? List.of() : List.copyOf(referencedPetIds);
        missingPetIds = missingPetIds == null ? List.of() : List.copyOf(missingPetIds);
        reason = reason == null ? "" : reason;
    }
}
