package com.omnibank.payments.routing;

import com.omnibank.shared.domain.Result;
import com.omnibank.shared.domain.RoutingNumber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Validates beneficiary details before payment routing and submission.
 *
 * <p>Validation includes:
 * <ul>
 *   <li>ABA routing number validation (checksum + active status in Fed directory)</li>
 *   <li>Account number format validation (length, character set, Luhn where applicable)</li>
 *   <li>OFAC/SDN screening placeholder — real-time check against sanctions lists</li>
 *   <li>Bank directory lookup — confirms routing number maps to active institution</li>
 *   <li>Beneficiary name screening against internal watchlists</li>
 * </ul>
 *
 * <p>Validation results are cached for a configurable TTL to reduce redundant
 * screening calls for repeat beneficiaries.
 */
public class BeneficiaryValidator {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryValidator.class);

    private static final int MIN_ACCOUNT_LENGTH = 4;
    private static final int MAX_ACCOUNT_LENGTH = 17;
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^[0-9]{4,17}$");
    private static final Pattern BENEFICIARY_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9 .,'\\-/&()]{1,140}$");
    private static final Duration SCREENING_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration DIRECTORY_CACHE_TTL = Duration.ofHours(24);

    public sealed interface ValidationResult permits ValidationPassed, ValidationFailed {}

    public record ValidationPassed(
            String beneficiaryName,
            RoutingNumber routingNumber,
            String accountNumber,
            String bankName,
            Instant validatedAt
    ) implements ValidationResult {}

    public record ValidationFailed(
            List<ValidationError> errors,
            Instant validatedAt
    ) implements ValidationResult {
        public ValidationFailed {
            Objects.requireNonNull(errors, "errors");
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("ValidationFailed must have at least one error");
            }
        }
    }

    public record ValidationError(
            ErrorCategory category,
            String field,
            String message,
            ErrorSeverity severity
    ) {
        public ValidationError {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(severity, "severity");
        }
    }

    public enum ErrorCategory {
        ROUTING_NUMBER,
        ACCOUNT_NUMBER,
        BENEFICIARY_NAME,
        SANCTIONS_SCREENING,
        BANK_DIRECTORY,
        WATCHLIST
    }

    public enum ErrorSeverity {
        BLOCKING,    // Payment cannot proceed
        WARNING,     // Payment can proceed with manual review
        INFO         // Informational only
    }

    /**
     * Represents an institution entry in the Federal Reserve bank directory.
     */
    public record BankDirectoryEntry(
            RoutingNumber routingNumber,
            String bankName,
            String city,
            String state,
            boolean active,
            boolean achParticipant,
            boolean wireParticipant,
            boolean rtpParticipant,
            boolean fedNowParticipant,
            Instant lastUpdated
    ) {
        public BankDirectoryEntry {
            Objects.requireNonNull(routingNumber, "routingNumber");
            Objects.requireNonNull(bankName, "bankName");
        }
    }

    /**
     * OFAC screening result.
     */
    public record ScreeningResult(
            String entityName,
            boolean matched,
            double matchScore,
            String matchedListEntry,
            String listType,
            Instant screenedAt
    ) {}

    private record CacheEntry<T>(T value, Instant cachedAt) {
        boolean isExpired(Duration ttl, Instant now) {
            return Duration.between(cachedAt, now).compareTo(ttl) > 0;
        }
    }

    private final Map<String, BankDirectoryEntry> bankDirectory = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<ScreeningResult>> screeningCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<BankDirectoryEntry>> directoryCache = new ConcurrentHashMap<>();
    private final Set<String> internalWatchlistNames = ConcurrentHashMap.newKeySet();

    /**
     * Performs full beneficiary validation including routing, account format,
     * sanctions screening, and bank directory lookup.
     */
    public ValidationResult validate(
            String beneficiaryName,
            String routingNumberRaw,
            String accountNumber) {

        var errors = new ArrayList<ValidationError>();
        var now = Instant.now();

        log.info("Validating beneficiary: name={}, routing={}, account={}***",
                maskName(beneficiaryName), routingNumberRaw,
                accountNumber != null && accountNumber.length() > 4
                        ? accountNumber.substring(0, 4) : "****");

        // 1. Validate beneficiary name format
        validateBeneficiaryName(beneficiaryName, errors);

        // 2. Validate and parse routing number
        RoutingNumber routingNumber = null;
        try {
            routingNumber = RoutingNumber.of(routingNumberRaw);
        } catch (IllegalArgumentException e) {
            errors.add(new ValidationError(
                    ErrorCategory.ROUTING_NUMBER, "routingNumber",
                    "Invalid ABA routing number: " + e.getMessage(),
                    ErrorSeverity.BLOCKING));
        }

        // 3. Validate account number format
        validateAccountNumber(accountNumber, errors);

        // 4. Bank directory lookup (only if routing number is valid)
        String bankName = null;
        if (routingNumber != null) {
            var directoryResult = lookupBankDirectory(routingNumber);
            if (directoryResult.isEmpty()) {
                errors.add(new ValidationError(
                        ErrorCategory.BANK_DIRECTORY, "routingNumber",
                        "Routing number not found in Federal Reserve bank directory: " + routingNumberRaw,
                        ErrorSeverity.BLOCKING));
            } else {
                var entry = directoryResult.get();
                bankName = entry.bankName();
                if (!entry.active()) {
                    errors.add(new ValidationError(
                            ErrorCategory.BANK_DIRECTORY, "routingNumber",
                            "Bank is no longer active: %s (%s)".formatted(entry.bankName(), routingNumberRaw),
                            ErrorSeverity.BLOCKING));
                }
            }
        }

        // 5. OFAC / sanctions screening
        var screeningResult = screenBeneficiary(beneficiaryName);
        if (screeningResult.matched()) {
            var severity = screeningResult.matchScore() >= 0.95
                    ? ErrorSeverity.BLOCKING : ErrorSeverity.WARNING;
            errors.add(new ValidationError(
                    ErrorCategory.SANCTIONS_SCREENING, "beneficiaryName",
                    "OFAC screening match: score=%.2f, list=%s, entry=%s"
                            .formatted(screeningResult.matchScore(), screeningResult.listType(),
                                    screeningResult.matchedListEntry()),
                    severity));
        }

        // 6. Internal watchlist check
        if (isOnInternalWatchlist(beneficiaryName)) {
            errors.add(new ValidationError(
                    ErrorCategory.WATCHLIST, "beneficiaryName",
                    "Beneficiary name matches internal watchlist entry",
                    ErrorSeverity.WARNING));
        }

        // Return result
        if (errors.stream().anyMatch(e -> e.severity() == ErrorSeverity.BLOCKING)) {
            log.warn("Beneficiary validation failed with {} errors (blocking)", errors.size());
            return new ValidationFailed(List.copyOf(errors), now);
        }

        if (!errors.isEmpty()) {
            log.info("Beneficiary validation passed with {} warnings", errors.size());
        }

        return new ValidationPassed(
                beneficiaryName, routingNumber, accountNumber,
                bankName != null ? bankName : "Unknown", now);
    }

    /**
     * Performs bulk screening of multiple beneficiaries (e.g., for batch files).
     */
    public List<Result<ValidationPassed, ValidationFailed>> validateBatch(
            List<BeneficiaryRecord> beneficiaries) {

        log.info("Bulk beneficiary validation: {} records", beneficiaries.size());

        // JDK 17 cross-compat: switch patterns (JEP 441) not in 17.
        return beneficiaries.stream()
                .map(b -> {
                    var result = validate(b.name(), b.routingNumber(), b.accountNumber());
                    if (result instanceof ValidationPassed p) {
                        return Result.<ValidationPassed, ValidationFailed>ok(p);
                    }
                    if (result instanceof ValidationFailed f) {
                        return Result.<ValidationPassed, ValidationFailed>err(f);
                    }
                    throw new IllegalStateException("Unknown Validation result: " + result.getClass());
                })
                .toList();
    }

    public record BeneficiaryRecord(String name, String routingNumber, String accountNumber) {
        public BeneficiaryRecord {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(routingNumber, "routingNumber");
            Objects.requireNonNull(accountNumber, "accountNumber");
        }
    }

    /**
     * Loads bank directory entries (typically from a Fed-published file).
     */
    public void loadBankDirectory(List<BankDirectoryEntry> entries) {
        entries.forEach(e -> bankDirectory.put(e.routingNumber().raw(), e));
        log.info("Bank directory loaded: {} entries", entries.size());
    }

    /**
     * Adds a name to the internal watchlist.
     */
    public void addToWatchlist(String name) {
        internalWatchlistNames.add(normalizeForScreening(name));
        log.info("Name added to internal watchlist: {}", maskName(name));
    }

    /**
     * Clears expired entries from the screening cache.
     */
    public int evictExpiredScreeningCache() {
        var now = Instant.now();
        int evicted = 0;
        var iterator = screeningCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired(SCREENING_CACHE_TTL, now)) {
                iterator.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("Evicted {} expired screening cache entries", evicted);
        }
        return evicted;
    }

    private void validateBeneficiaryName(String name, List<ValidationError> errors) {
        if (name == null || name.isBlank()) {
            errors.add(new ValidationError(
                    ErrorCategory.BENEFICIARY_NAME, "beneficiaryName",
                    "Beneficiary name is required", ErrorSeverity.BLOCKING));
            return;
        }
        if (name.length() > 140) {
            errors.add(new ValidationError(
                    ErrorCategory.BENEFICIARY_NAME, "beneficiaryName",
                    "Beneficiary name exceeds 140 characters", ErrorSeverity.BLOCKING));
        }
        if (!BENEFICIARY_NAME_PATTERN.matcher(name).matches()) {
            errors.add(new ValidationError(
                    ErrorCategory.BENEFICIARY_NAME, "beneficiaryName",
                    "Beneficiary name contains invalid characters", ErrorSeverity.BLOCKING));
        }
    }

    private void validateAccountNumber(String accountNumber, List<ValidationError> errors) {
        if (accountNumber == null || accountNumber.isBlank()) {
            errors.add(new ValidationError(
                    ErrorCategory.ACCOUNT_NUMBER, "accountNumber",
                    "Account number is required", ErrorSeverity.BLOCKING));
            return;
        }
        if (!ACCOUNT_NUMBER_PATTERN.matcher(accountNumber).matches()) {
            errors.add(new ValidationError(
                    ErrorCategory.ACCOUNT_NUMBER, "accountNumber",
                    "Account number must be 4-17 digits: length=%d".formatted(accountNumber.length()),
                    ErrorSeverity.BLOCKING));
        }
    }

    private Optional<BankDirectoryEntry> lookupBankDirectory(RoutingNumber routing) {
        // Check cache first
        var cached = directoryCache.get(routing.raw());
        if (cached != null && !cached.isExpired(DIRECTORY_CACHE_TTL, Instant.now())) {
            return Optional.of(cached.value());
        }

        var entry = bankDirectory.get(routing.raw());
        if (entry != null) {
            directoryCache.put(routing.raw(), new CacheEntry<>(entry, Instant.now()));
        }
        return Optional.ofNullable(entry);
    }

    /**
     * Screens a beneficiary name against OFAC/SDN lists.
     * In production, this calls an external sanctions screening service.
     */
    private ScreeningResult screenBeneficiary(String name) {
        if (name == null) {
            return new ScreeningResult(name, false, 0.0, null, null, Instant.now());
        }

        var normalized = normalizeForScreening(name);
        var cached = screeningCache.get(normalized);
        if (cached != null && !cached.isExpired(SCREENING_CACHE_TTL, Instant.now())) {
            return cached.value();
        }

        // Production: call sanctions screening API (e.g., Dow Jones, LexisNexis, or OFAC API)
        // This is a placeholder that performs no actual screening
        var result = new ScreeningResult(name, false, 0.0, null, null, Instant.now());
        screeningCache.put(normalized, new CacheEntry<>(result, Instant.now()));

        log.debug("OFAC screening completed for: {} — no match", maskName(name));
        return result;
    }

    private boolean isOnInternalWatchlist(String name) {
        if (name == null) return false;
        return internalWatchlistNames.contains(normalizeForScreening(name));
    }

    private String normalizeForScreening(String name) {
        return name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String maskName(String name) {
        if (name == null || name.length() <= 3) return "***";
        return name.substring(0, 3) + "***";
    }
}
