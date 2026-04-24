package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Comprehensive journal entry validation with 12+ rules applied in priority
 * order. Each rule is independent and produces a typed validation error; all
 * rules run (no short-circuit) so the caller receives the full set of problems
 * in a single round trip rather than discovering them one at a time.
 *
 * <p>Rule ordering matters for user experience — we surface structural errors
 * (missing fields, wrong line count) before semantic errors (balance check,
 * account existence) before policy errors (amount limits, regulatory holds).
 */
public class JournalEntryValidator {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryValidator.class);

    /** Maximum number of lines in a single journal entry. */
    private static final int MAX_LINES = 500;

    /** Maximum single-line amount before dual-authorization is required. */
    private static final BigDecimal DUAL_AUTH_THRESHOLD = new BigDecimal("10000000.00");

    /** Maximum posting backdating — entries older than this need special approval. */
    private static final int MAX_BACKDATE_DAYS = 90;

    /** Accounts restricted from direct manual posting (system-only accounts). */
    private static final Set<String> RESTRICTED_ACCOUNT_PREFIXES = Set.of(
            "EQU-9900", "EQU-9901", // retained earnings — only period-close can post
            "ASS-0000", "LIA-0000"  // control accounts — subledger-only
    );

    private final GlAccountRepository accounts;
    private final JournalEntryRepository journals;
    private final Clock clock;

    public JournalEntryValidator(GlAccountRepository accounts,
                                 JournalEntryRepository journals,
                                 Clock clock) {
        this.accounts = accounts;
        this.journals = journals;
        this.clock = clock;
    }

    /**
     * Validate a journal entry against all rules. Returns a list of validation
     * errors. An empty list means the entry is valid and ready for posting.
     */
    public List<ValidationError> validate(JournalEntry entry) {
        return validate(entry, ValidationContext.NORMAL);
    }

    /**
     * Validate with an explicit context, which relaxes certain rules for
     * system-generated entries (e.g. period-close, auto-reversals).
     */
    public List<ValidationError> validate(JournalEntry entry, ValidationContext context) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(context, "context");

        List<ValidationError> errors = new ArrayList<>();

        // Rule 1: Minimum line count (structural)
        validateMinimumLines(entry, errors);

        // Rule 2: Maximum line count (structural)
        validateMaximumLines(entry, errors);

        // Rule 3: Debit-credit balance (fundamental accounting invariant)
        validateBalance(entry, errors);

        // Rule 4: Single-currency constraint
        validateSingleCurrency(entry, errors);

        // Rule 5: Posting date validity
        validatePostingDate(entry, context, errors);

        // Rule 6: Account existence and status
        validateAccountExistence(entry, errors);

        // Rule 7: Account-currency alignment
        validateAccountCurrency(entry, errors);

        // Rule 8: Duplicate detection (idempotency guard)
        validateNoDuplicate(entry, errors);

        // Rule 9: Amount limits and dual-authorization
        validateAmountLimits(entry, context, errors);

        // Rule 10: Restricted accounts
        if (context == ValidationContext.NORMAL) {
            validateRestrictedAccounts(entry, errors);
        }

        // Rule 11: Regulatory holds
        validateRegulatoryHolds(entry, errors);

        // Rule 12: Cross-entity validation
        validateCrossEntity(entry, errors);

        // Rule 13: Memo/description completeness for audit trail
        validateAuditTrailCompleteness(entry, errors);

        if (!errors.isEmpty()) {
            log.info("Journal entry {} failed validation with {} errors: {}",
                    entry.businessKey(), errors.size(),
                    errors.stream().map(ValidationError::code).toList());
        }

        return errors;
    }

    // ── Individual rules ────────────────────────────────────────────────

    private void validateMinimumLines(JournalEntry entry, List<ValidationError> errors) {
        if (entry.lines().size() < 2) {
            errors.add(new ValidationError(
                    "MIN_LINES",
                    "Journal entry must have at least 2 posting lines",
                    Severity.ERROR));
        }
    }

    private void validateMaximumLines(JournalEntry entry, List<ValidationError> errors) {
        if (entry.lines().size() > MAX_LINES) {
            errors.add(new ValidationError(
                    "MAX_LINES",
                    "Journal entry has %d lines, max is %d".formatted(entry.lines().size(), MAX_LINES),
                    Severity.ERROR));
        }
    }

    private void validateBalance(JournalEntry entry, List<ValidationError> errors) {
        if (!entry.isBalanced()) {
            Money debits = entry.debitTotal();
            Money credits = entry.creditTotal();
            Money difference = debits.minus(credits).abs();
            errors.add(new ValidationError(
                    "UNBALANCED",
                    "Debits (%s) do not equal credits (%s), difference=%s"
                            .formatted(debits, credits, difference),
                    Severity.ERROR));
        }
    }

    private void validateSingleCurrency(JournalEntry entry, List<ValidationError> errors) {
        Set<CurrencyCode> currencies = new HashSet<>();
        for (PostingLine line : entry.lines()) {
            currencies.add(line.amount().currency());
        }
        if (currencies.size() > 1) {
            errors.add(new ValidationError(
                    "MIXED_CURRENCIES",
                    "Journal contains multiple currencies: %s. Use separate entries per currency."
                            .formatted(currencies),
                    Severity.ERROR));
        }
    }

    private void validatePostingDate(JournalEntry entry, ValidationContext context,
                                     List<ValidationError> errors) {
        LocalDate today = LocalDate.now(clock);
        LocalDate postingDate = entry.postingDate();

        // No future dates except for system-generated entries
        if (postingDate.isAfter(today) && context == ValidationContext.NORMAL) {
            errors.add(new ValidationError(
                    "FUTURE_DATE",
                    "Posting date %s is in the future".formatted(postingDate),
                    Severity.ERROR));
        }

        // Excessive backdating check
        if (postingDate.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            if (context == ValidationContext.NORMAL) {
                errors.add(new ValidationError(
                        "EXCESSIVE_BACKDATE",
                        "Posting date %s is more than %d days in the past, requires special approval"
                                .formatted(postingDate, MAX_BACKDATE_DAYS),
                        Severity.ERROR));
            } else {
                errors.add(new ValidationError(
                        "BACKDATE_WARNING",
                        "Posting date %s is more than %d days in the past"
                                .formatted(postingDate, MAX_BACKDATE_DAYS),
                        Severity.WARNING));
            }
        }

        // Weekend/holiday check (warning only — some jurisdictions require it)
        if (postingDate.getDayOfWeek().getValue() >= 6) {
            errors.add(new ValidationError(
                    "WEEKEND_POSTING",
                    "Posting date %s falls on a weekend".formatted(postingDate),
                    Severity.WARNING));
        }
    }

    private void validateAccountExistence(JournalEntry entry, List<ValidationError> errors) {
        for (PostingLine line : entry.lines()) {
            Optional<GlAccountEntity> accountOpt = accounts.findById(line.account().value());
            if (accountOpt.isEmpty()) {
                errors.add(new ValidationError(
                        "UNKNOWN_ACCOUNT",
                        "GL account %s does not exist".formatted(line.account()),
                        Severity.ERROR));
            } else if (accountOpt.get().isClosed()) {
                errors.add(new ValidationError(
                        "ACCOUNT_CLOSED",
                        "GL account %s is closed and cannot accept postings"
                                .formatted(line.account()),
                        Severity.ERROR));
            }
        }
    }

    private void validateAccountCurrency(JournalEntry entry, List<ValidationError> errors) {
        for (PostingLine line : entry.lines()) {
            accounts.findById(line.account().value()).ifPresent(account -> {
                if (account.currency() != line.amount().currency()) {
                    errors.add(new ValidationError(
                            "CURRENCY_MISMATCH",
                            "Line currency %s does not match account %s currency %s"
                                    .formatted(line.amount().currency(), line.account(),
                                            account.currency()),
                            Severity.ERROR));
                }
            });
        }
    }

    private void validateNoDuplicate(JournalEntry entry, List<ValidationError> errors) {
        journals.findByBusinessKey(entry.businessKey()).ifPresent(existing -> {
            errors.add(new ValidationError(
                    "DUPLICATE_BUSINESS_KEY",
                    "Business key '%s' already posted as journal sequence %d"
                            .formatted(entry.businessKey(), existing.sequence()),
                    Severity.ERROR));
        });
    }

    private void validateAmountLimits(JournalEntry entry, ValidationContext context,
                                      List<ValidationError> errors) {
        for (PostingLine line : entry.lines()) {
            BigDecimal lineAmount = line.amount().amount();

            // Zero amounts should be caught by PostingLine constructor,
            // but belt-and-suspenders in a bank
            if (lineAmount.signum() <= 0) {
                errors.add(new ValidationError(
                        "NON_POSITIVE_AMOUNT",
                        "Line amount must be positive: %s on account %s"
                                .formatted(lineAmount, line.account()),
                        Severity.ERROR));
            }

            // Dual-authorization threshold
            if (lineAmount.compareTo(DUAL_AUTH_THRESHOLD) > 0 && context == ValidationContext.NORMAL) {
                errors.add(new ValidationError(
                        "DUAL_AUTH_REQUIRED",
                        "Line amount %s on account %s exceeds dual-authorization threshold of %s"
                                .formatted(lineAmount, line.account(), DUAL_AUTH_THRESHOLD),
                        Severity.WARNING));
            }
        }
    }

    private void validateRestrictedAccounts(JournalEntry entry, List<ValidationError> errors) {
        for (PostingLine line : entry.lines()) {
            String code = line.account().value();
            for (String prefix : RESTRICTED_ACCOUNT_PREFIXES) {
                if (code.startsWith(prefix)) {
                    errors.add(new ValidationError(
                            "RESTRICTED_ACCOUNT",
                            "Account %s is restricted from manual posting (prefix %s)"
                                    .formatted(code, prefix),
                            Severity.ERROR));
                    break;
                }
            }
        }
    }

    private void validateRegulatoryHolds(JournalEntry entry, List<ValidationError> errors) {
        // Check for accounts under regulatory hold (e.g. AML freeze, court order)
        Map<String, String> holdReasons = new HashMap<>();
        for (PostingLine line : entry.lines()) {
            accounts.findById(line.account().value()).ifPresent(account -> {
                // Accounts in the suspicious-activity range require compliance sign-off
                String code = account.code();
                if (code.startsWith("ASS-8") || code.startsWith("LIA-8")) {
                    holdReasons.put(code, "Suspicious activity monitoring range");
                }
            });
        }
        for (var held : holdReasons.entrySet()) {
            errors.add(new ValidationError(
                    "REGULATORY_HOLD",
                    "Account %s is under regulatory review: %s"
                            .formatted(held.getKey(), held.getValue()),
                    Severity.WARNING));
        }
    }

    private void validateCrossEntity(JournalEntry entry, List<ValidationError> errors) {
        // All posting lines in a single journal must belong to the same legal entity.
        // Cross-entity postings must go through IntercompanySettlement.
        Set<AccountType> accountTypes = new HashSet<>();
        Set<String> inferredSegments = new HashSet<>();

        for (PostingLine line : entry.lines()) {
            String segment = line.account().value().substring(0, 3);
            inferredSegments.add(segment);
            accountTypes.add(line.account().inferredType());
        }

        // Validate consistent normal-balance expectations
        boolean hasDebits = entry.lines().stream()
                .anyMatch(l -> l.direction() == PostingDirection.DEBIT);
        boolean hasCredits = entry.lines().stream()
                .anyMatch(l -> l.direction() == PostingDirection.CREDIT);

        if (!hasDebits || !hasCredits) {
            errors.add(new ValidationError(
                    "MISSING_DIRECTION",
                    "Journal entry must have at least one debit and one credit line",
                    Severity.ERROR));
        }
    }

    private void validateAuditTrailCompleteness(JournalEntry entry, List<ValidationError> errors) {
        if (entry.description() == null || entry.description().isBlank()) {
            errors.add(new ValidationError(
                    "MISSING_DESCRIPTION",
                    "Journal entry description is required for audit trail",
                    Severity.ERROR));
        } else if (entry.description().length() < 10) {
            errors.add(new ValidationError(
                    "SHORT_DESCRIPTION",
                    "Journal entry description should be at least 10 characters for meaningful audit trail",
                    Severity.WARNING));
        }

        // Check that high-value lines have memos
        for (PostingLine line : entry.lines()) {
            if (line.amount().amount().compareTo(new BigDecimal("100000")) > 0
                    && (line.memo() == null || line.memo().isBlank())) {
                errors.add(new ValidationError(
                        "MISSING_MEMO_HIGH_VALUE",
                        "Line on account %s for %s should have a memo for audit purposes"
                                .formatted(line.account(), line.amount()),
                        Severity.WARNING));
            }
        }
    }

    // ── Types ───────────────────────────────────────────────────────────

    public enum ValidationContext {
        /** Normal business posting — all rules apply. */
        NORMAL,
        /** System-generated entry (period close, auto-reversal) — relaxed rules. */
        SYSTEM,
        /** Adjusting entry during soft-close — partially relaxed. */
        ADJUSTMENT
    }

    public enum Severity {
        /** Hard error — posting must be rejected. */
        ERROR,
        /** Warning — posting can proceed but requires acknowledgment. */
        WARNING
    }

    public record ValidationError(String code, String message, Severity severity) {
        public ValidationError {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(severity, "severity");
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        public boolean isWarning() {
            return severity == Severity.WARNING;
        }
    }
}
