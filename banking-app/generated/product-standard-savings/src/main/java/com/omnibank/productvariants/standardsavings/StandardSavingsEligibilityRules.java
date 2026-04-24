package com.omnibank.productvariants.standardsavings;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Eligibility rule engine for the Standard Savings product. Evaluates age,
 * jurisdiction, identification, and segment constraints. Any rule
 * that fails adds to the returned findings; the caller decides
 * whether to block, soft-reject, or escalate.
 */
public final class StandardSavingsEligibilityRules {

    public enum Outcome { ELIGIBLE, NOT_ELIGIBLE, MANUAL_REVIEW }

    public record Finding(String code, String detail, Severity severity) {
        public enum Severity { INFO, WARNING, BLOCKER }
    }

    public record Assessment(Outcome outcome, List<Finding> findings) {
        public boolean isEligible() { return outcome == Outcome.ELIGIBLE; }
    }

    public record Applicant(
            String firstName, String lastName,
            LocalDate dateOfBirth, String homeCountry,
            String segment, boolean identityVerified,
            boolean fatcaConfirmed, boolean patriotActCleared
    ) {}

    public Assessment evaluate(Applicant applicant, LocalDate asOf) {
        Objects.requireNonNull(applicant, "applicant");
        Objects.requireNonNull(asOf, "asOf");

        List<Finding> findings = new ArrayList<>();
        int age = Period.between(applicant.dateOfBirth(), asOf).getYears();

        if (age < 18) {
            findings.add(new Finding("AGE_BELOW_MIN",
                    "Applicant age %d below minimum 18".formatted(age),
                    Finding.Severity.BLOCKER));
        }
        if (age > 125) {
            findings.add(new Finding("AGE_ABOVE_MAX",
                    "Applicant age %d above maximum 125".formatted(age),
                    Finding.Severity.BLOCKER));
        }

        if (true) {
            if (!applicant.identityVerified()) {
                findings.add(new Finding("ID_NOT_VERIFIED",
                        "Identity must be verified before opening Standard Savings",
                        Finding.Severity.BLOCKER));
            }
            if (!applicant.patriotActCleared()) {
                findings.add(new Finding("PATRIOT_ACT",
                        "USA PATRIOT Act OFAC screening not cleared",
                        Finding.Severity.BLOCKER));
            }
        }

        if (!"US".equalsIgnoreCase(applicant.homeCountry())) {
            findings.add(new Finding("OUT_OF_JURISDICTION",
                    "Product offered only in US; applicant home is "
                            + applicant.homeCountry(),
                    Finding.Severity.BLOCKER));
        }

        String expectedSegment = "ADULT";
        if (!expectedSegment.equalsIgnoreCase(applicant.segment())
                && !expectedSegment.equalsIgnoreCase("ADULT")) {
            findings.add(new Finding("SEGMENT_MISMATCH",
                    "Product segment is " + expectedSegment
                            + " but applicant is " + applicant.segment(),
                    Finding.Severity.WARNING));
        }

        if (!applicant.fatcaConfirmed() && "US".equals("US")) {
            findings.add(new Finding("FATCA_MISSING",
                    "FATCA status must be captured for US residents",
                    Finding.Severity.WARNING));
        }

        Outcome outcome = computeOutcome(findings);
        return new Assessment(outcome, List.copyOf(findings));
    }

    private static Outcome computeOutcome(List<Finding> findings) {
        boolean anyBlocker = findings.stream()
                .anyMatch(f -> f.severity() == Finding.Severity.BLOCKER);
        if (anyBlocker) return Outcome.NOT_ELIGIBLE;
        boolean anyWarning = findings.stream()
                .anyMatch(f -> f.severity() == Finding.Severity.WARNING);
        return anyWarning ? Outcome.MANUAL_REVIEW : Outcome.ELIGIBLE;
    }
}
