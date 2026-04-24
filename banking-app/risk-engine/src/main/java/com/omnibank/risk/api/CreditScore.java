package com.omnibank.risk.api;

import java.time.Instant;

public record CreditScore(int fico, Grade grade, Instant computedAt) {

    public enum Grade { AAA, AA, A, BBB, BB, B, CCC, CC, C, D }
}
