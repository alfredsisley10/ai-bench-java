package com.omnibank.payments.internal;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway for real-time sanctions screening against OFAC, SDN, and other
 * restricted-party lists.
 *
 * <p>All outbound payments must be screened before submission to any rail. This
 * gateway:
 * <ul>
 *   <li>Screens originator and beneficiary names/accounts against OFAC SDN list</li>
 *   <li>Supports configurable match threshold (fuzzy matching for name variants)</li>
 *   <li>Caches screening results with configurable TTL to avoid redundant calls</li>
 *   <li>Provides bulk screening for batch payment files</li>
 *   <li>Tracks screening decisions for compliance audit</li>
 * </ul>
 *
 * <p>Screening disposition:
 * <ul>
 *   <li>CLEAR — no match found, payment can proceed</li>
 *   <li>POTENTIAL_MATCH — fuzzy match below threshold, proceeds with logging</li>
 *   <li>MATCH — high-confidence match, payment blocked pending review</li>
 *   <li>ERROR — screening service unavailable, payment held per policy</li>
 * </ul>
 *
 * <p>In production, this delegates to an external sanctions screening provider
 * (e.g., Dow Jones, Accuity, LexisNexis). The stub implementation here returns
 * CLEAR for all screenings but preserves the full interface contract.
 */
public class SanctionsScreeningGateway {

    private static final Logger log = LoggerFactory.getLogger(SanctionsScreeningGateway.class);

    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(30);
    private static final double DEFAULT_MATCH_THRESHOLD = 0.85;

    public enum ScreeningDisposition {
        CLEAR,
        POTENTIAL_MATCH,
        MATCH,
        ERROR
    }

    public enum ScreeningList {
        OFAC_SDN("OFAC Specially Designated Nationals"),
        OFAC_CONSOLIDATED("OFAC Consolidated Sanctions List"),
        EU_SANCTIONS("EU Consolidated Financial Sanctions"),
        UN_SANCTIONS("UN Security Council Sanctions"),
        UK_SANCTIONS("UK HM Treasury Sanctions"),
        BIS_ENTITY("BIS Entity List"),
        INTERNAL_WATCHLIST("Internal Bank Watchlist");

        private final String description;

        ScreeningList(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public sealed interface ScreeningSubject permits IndividualSubject, EntitySubject, PaymentSubject {}

    public record IndividualSubject(
            String fullName,
            String dateOfBirth,
            String nationality,
            String idNumber,
            String address
    ) implements ScreeningSubject {
        public IndividualSubject {
            Objects.requireNonNull(fullName, "fullName");
        }
    }

    public record EntitySubject(
            String entityName,
            String registrationCountry,
            String registrationNumber,
            String address
    ) implements ScreeningSubject {
        public EntitySubject {
            Objects.requireNonNull(entityName, "entityName");
        }
    }

    public record PaymentSubject(
            PaymentId paymentId,
            String originatorName,
            String beneficiaryName,
            String beneficiaryAccount,
            String beneficiaryBankName,
            String memo
    ) implements ScreeningSubject {
        public PaymentSubject {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(originatorName, "originatorName");
            Objects.requireNonNull(beneficiaryName, "beneficiaryName");
        }
    }

    public record ScreeningResult(
            String screeningId,
            ScreeningSubject subject,
            ScreeningDisposition disposition,
            double highestMatchScore,
            List<MatchDetail> matches,
            List<ScreeningList> listsScreened,
            Duration screeningDuration,
            Instant screenedAt
    ) {
        public ScreeningResult {
            Objects.requireNonNull(screeningId, "screeningId");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(listsScreened, "listsScreened");
            Objects.requireNonNull(screenedAt, "screenedAt");
            matches = matches != null ? List.copyOf(matches) : List.of();
        }
    }

    public record MatchDetail(
            ScreeningList list,
            String matchedName,
            String matchedEntityId,
            double matchScore,
            String matchType,
            String listEntryDetails
    ) {
        public MatchDetail {
            Objects.requireNonNull(list, "list");
            Objects.requireNonNull(matchedName, "matchedName");
        }
    }

    public record BulkScreeningResult(
            String batchId,
            int totalSubjects,
            int cleared,
            int potentialMatches,
            int matches,
            int errors,
            List<ScreeningResult> results,
            Duration totalDuration,
            Instant completedAt
    ) {}

    private record CacheEntry(ScreeningResult result, Instant cachedAt) {
        boolean isExpired(Duration ttl, Instant now) {
            return Duration.between(cachedAt, now).compareTo(ttl) > 0;
        }
    }

    private final Clock clock;
    private final Map<String, CacheEntry> screeningCache = new ConcurrentHashMap<>();
    private final Map<String, ScreeningResult> screeningHistory = new ConcurrentHashMap<>();
    private double matchThreshold = DEFAULT_MATCH_THRESHOLD;
    private Duration cacheTtl = DEFAULT_CACHE_TTL;
    private List<ScreeningList> enabledLists = List.of(
            ScreeningList.OFAC_SDN, ScreeningList.OFAC_CONSOLIDATED,
            ScreeningList.INTERNAL_WATCHLIST);

    public SanctionsScreeningGateway(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Screens a single subject against all enabled sanctions lists.
     */
    public ScreeningResult screen(ScreeningSubject subject) {
        var now = Timestamp.now(clock);
        var cacheKey = computeCacheKey(subject);

        // Check cache
        var cached = screeningCache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtl, now)) {
            log.debug("Screening cache hit: subject={}", subjectSummary(subject));
            return cached.result();
        }

        var start = System.nanoTime();
        var screeningId = "SCR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        log.info("Initiating sanctions screening: id={}, subject={}, lists={}",
                screeningId, subjectSummary(subject), enabledLists.size());

        // Perform screening against each enabled list
        var allMatches = new ArrayList<MatchDetail>();
        double highestScore = 0.0;

        for (var list : enabledLists) {
            var matches = screenAgainstList(subject, list);
            allMatches.addAll(matches);
            for (var match : matches) {
                if (match.matchScore() > highestScore) {
                    highestScore = match.matchScore();
                }
            }
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Determine disposition based on match scores
        var disposition = determineDisposition(highestScore, allMatches);

        var result = new ScreeningResult(
                screeningId, subject, disposition, highestScore,
                allMatches, List.copyOf(enabledLists), elapsed, now);

        // Cache and store
        screeningCache.put(cacheKey, new CacheEntry(result, now));
        screeningHistory.put(screeningId, result);

        log.info("Screening completed: id={}, disposition={}, score={}, matches={}, duration={}ms",
                screeningId, disposition, highestScore, allMatches.size(), elapsed.toMillis());

        return result;
    }

    /**
     * Screens a payment (both originator and beneficiary) in a single call.
     */
    public ScreeningResult screenPayment(PaymentSubject paymentSubject) {
        log.info("Screening payment: paymentId={}, originator={}, beneficiary={}",
                paymentSubject.paymentId(),
                maskName(paymentSubject.originatorName()),
                maskName(paymentSubject.beneficiaryName()));

        return screen(paymentSubject);
    }

    /**
     * Performs bulk screening of multiple subjects. Used for batch file processing.
     */
    public BulkScreeningResult screenBulk(String batchId, List<ScreeningSubject> subjects) {
        var start = System.nanoTime();
        var now = Timestamp.now(clock);

        log.info("Initiating bulk screening: batchId={}, subjects={}", batchId, subjects.size());

        var results = new ArrayList<ScreeningResult>();
        int cleared = 0, potentialMatches = 0, matches = 0, errors = 0;

        for (var subject : subjects) {
            try {
                var result = screen(subject);
                results.add(result);
                switch (result.disposition()) {
                    case CLEAR -> cleared++;
                    case POTENTIAL_MATCH -> potentialMatches++;
                    case MATCH -> matches++;
                    case ERROR -> errors++;
                }
            } catch (Exception e) {
                log.error("Bulk screening error for subject: {}", subjectSummary(subject), e);
                errors++;
            }
        }

        var totalDuration = Duration.ofNanos(System.nanoTime() - start);

        log.info("Bulk screening completed: batchId={}, cleared={}, potential={}, matches={}, errors={}, duration={}ms",
                batchId, cleared, potentialMatches, matches, errors, totalDuration.toMillis());

        return new BulkScreeningResult(
                batchId, subjects.size(), cleared, potentialMatches, matches, errors,
                List.copyOf(results), totalDuration, now);
    }

    /**
     * Retrieves a previous screening result by ID.
     */
    public Optional<ScreeningResult> getScreeningResult(String screeningId) {
        return Optional.ofNullable(screeningHistory.get(screeningId));
    }

    /**
     * Updates the match threshold. Higher values require closer matches to trigger alerts.
     */
    public void setMatchThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Match threshold must be between 0.0 and 1.0");
        }
        this.matchThreshold = threshold;
        log.info("Sanctions screening match threshold updated: {}", threshold);
    }

    /**
     * Updates the cache TTL.
     */
    public void setCacheTtl(Duration ttl) {
        this.cacheTtl = Objects.requireNonNull(ttl, "ttl");
        log.info("Sanctions screening cache TTL updated: {}", ttl);
    }

    /**
     * Configures which sanctions lists are enabled for screening.
     */
    public void setEnabledLists(List<ScreeningList> lists) {
        this.enabledLists = List.copyOf(lists);
        log.info("Enabled sanctions lists updated: {}", lists);
    }

    /**
     * Evicts expired cache entries.
     */
    public int evictExpiredCache() {
        var now = Timestamp.now(clock);
        int evicted = 0;
        var iterator = screeningCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired(cacheTtl, now)) {
                iterator.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("Evicted {} expired screening cache entries", evicted);
        }
        return evicted;
    }

    public int cacheSize() {
        return screeningCache.size();
    }

    public int historySize() {
        return screeningHistory.size();
    }

    /**
     * Performs screening against a specific list.
     * In production, this delegates to the external screening API.
     */
    private List<MatchDetail> screenAgainstList(ScreeningSubject subject, ScreeningList list) {
        // Stub implementation — production would call external screening service
        // such as Dow Jones Risk & Compliance, NICE Actimize, or direct OFAC API
        log.debug("Screening against list: {}, subject: {}", list, subjectSummary(subject));

        // Return empty matches (no hits) for the stub
        return List.of();
    }

    private ScreeningDisposition determineDisposition(double highestScore, List<MatchDetail> matches) {
        if (matches.isEmpty()) {
            return ScreeningDisposition.CLEAR;
        }
        if (highestScore >= matchThreshold) {
            return ScreeningDisposition.MATCH;
        }
        if (highestScore >= matchThreshold * 0.8) {
            return ScreeningDisposition.POTENTIAL_MATCH;
        }
        return ScreeningDisposition.CLEAR;
    }

    // JDK 17 cross-compat: pattern matching for switch (JEP 441) is
    // stable in 21 and not available in 17 (not even as preview).
    // Rewritten as instanceof chains, which ARE available in 17 via
    // JEP 394 (pattern matching for instanceof).
    private String computeCacheKey(ScreeningSubject subject) {
        if (subject instanceof IndividualSubject s) return "IND:" + normalize(s.fullName());
        if (subject instanceof EntitySubject s) return "ENT:" + normalize(s.entityName());
        if (subject instanceof PaymentSubject s) return "PMT:" + normalize(s.originatorName()) + "|" + normalize(s.beneficiaryName());
        throw new IllegalStateException("Unknown ScreeningSubject subtype: " + subject.getClass());
    }

    private String subjectSummary(ScreeningSubject subject) {
        if (subject instanceof IndividualSubject s) return "Individual(%s)".formatted(maskName(s.fullName()));
        if (subject instanceof EntitySubject s) return "Entity(%s)".formatted(maskName(s.entityName()));
        if (subject instanceof PaymentSubject s) return "Payment(%s, %s->%s)".formatted(
                s.paymentId(), maskName(s.originatorName()), maskName(s.beneficiaryName()));
        throw new IllegalStateException("Unknown ScreeningSubject subtype: " + subject.getClass());
    }

    private String maskName(String name) {
        if (name == null || name.length() <= 3) return "***";
        return name.substring(0, 3) + "***";
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
