package com.omnibank.fraud.api;

import java.util.List;

public record FraudDecision(Verdict verdict, int score, List<String> signals) {

    public enum Verdict { PASS, REVIEW, BLOCK }
}
