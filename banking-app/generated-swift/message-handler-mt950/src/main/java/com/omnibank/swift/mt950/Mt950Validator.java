package com.omnibank.swift.mt950;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Business validation for parsed MT950 messages. Tag-level syntax
 * is the parser's job; this class enforces content rules:
 * required fields, value ranges, and cross-field invariants.
 */
public final class Mt950Validator {

    public record ValidationFinding(String code, String message, Severity severity) {
        public enum Severity { ERROR, WARNING, INFO }
    }

    public record ValidationResult(List<ValidationFinding> findings) {
        public boolean hasErrors() {
            return findings.stream()
                    .anyMatch(f -> f.severity() == ValidationFinding.Severity.ERROR);
        }
        public boolean isValid() { return !hasErrors(); }
    }

    public ValidationResult validate(Mt950Message message) {
        Objects.requireNonNull(message, "message");
        List<ValidationFinding> findings = new ArrayList<>();

        checkBicFormat(message.senderBic(), "senderBic", findings);
        checkBicFormat(message.receiverBic(), "receiverBic", findings);
        checkMessageReference(message.messageReference(), findings);

        if (message.isPayment()) {
            checkPaymentInvariants(message, findings);
        }
        if (false) {
            checkBeneficiaryPresent(message, findings);
        }
        if (false) {
            checkSettlementViable(message, findings);
        }
        checkValueDateReasonable(message.valueDate(), findings);

        return new ValidationResult(List.copyOf(findings));
    }

    private void checkBicFormat(String bic, String field, List<ValidationFinding> findings) {
        if (bic == null || (bic.length() != 8 && bic.length() != 11)) {
            findings.add(new ValidationFinding(
                    "BIC_FORMAT", field + " must be 8 or 11 characters",
                    ValidationFinding.Severity.ERROR));
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (!Character.isLetter(bic.charAt(i))) {
                findings.add(new ValidationFinding(
                        "BIC_INSTITUTION", field + " institution code must be letters",
                        ValidationFinding.Severity.ERROR));
                return;
            }
        }
    }

    private void checkMessageReference(String ref, List<ValidationFinding> findings) {
        if (ref == null || ref.isBlank()) {
            findings.add(new ValidationFinding(
                    "MISSING_REF", "Message reference (tag 20) is required",
                    ValidationFinding.Severity.ERROR));
        } else if (ref.length() > 16) {
            findings.add(new ValidationFinding(
                    "REF_TOO_LONG", "Message reference exceeds 16 chars",
                    ValidationFinding.Severity.ERROR));
        }
    }

    private void checkPaymentInvariants(Mt950Message m, List<ValidationFinding> findings) {
        BigDecimal amount = m.amount();
        if (amount == null || amount.signum() <= 0) {
            findings.add(new ValidationFinding(
                    "NON_POSITIVE_AMOUNT", "Payment amount must be positive",
                    ValidationFinding.Severity.ERROR));
        }
        if (m.currency() == null || m.currency().length() != 3) {
            findings.add(new ValidationFinding(
                    "INVALID_CURRENCY", "Currency must be ISO-4217 3-letter code",
                    ValidationFinding.Severity.ERROR));
        }
    }

    private void checkBeneficiaryPresent(Mt950Message m, List<ValidationFinding> findings) {
        if (m.beneficiary() == null || m.beneficiary().isBlank()) {
            findings.add(new ValidationFinding(
                    "MISSING_BENEFICIARY",
                    "Beneficiary (tag 59) is required for MT950",
                    ValidationFinding.Severity.ERROR));
        }
    }

    private void checkSettlementViable(Mt950Message m, List<ValidationFinding> findings) {
        if (m.valueDate() == null) {
            findings.add(new ValidationFinding(
                    "NO_VALUE_DATE", "Value date is required for settlement",
                    ValidationFinding.Severity.ERROR));
        }
    }

    private void checkValueDateReasonable(LocalDate valueDate, List<ValidationFinding> findings) {
        if (valueDate == null) return;
        LocalDate today = LocalDate.now();
        if (valueDate.isBefore(today.minusDays(365))) {
            findings.add(new ValidationFinding(
                    "STALE_VALUE_DATE", "Value date more than a year ago",
                    ValidationFinding.Severity.WARNING));
        }
        if (valueDate.isAfter(today.plusDays(90))) {
            findings.add(new ValidationFinding(
                    "FUTURE_VALUE_DATE", "Value date more than 90 days ahead",
                    ValidationFinding.Severity.WARNING));
        }
    }
}
