package com.omnibank.compliance.api;

import java.util.List;

public record SanctionsScreeningResult(boolean hit, int confidenceScore, List<String> matchingLists) {

    public static SanctionsScreeningResult clean() {
        return new SanctionsScreeningResult(false, 0, List.of());
    }
}
