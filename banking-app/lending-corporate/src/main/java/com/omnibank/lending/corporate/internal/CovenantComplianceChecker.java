package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.Covenant;
import com.omnibank.lending.corporate.api.CovenantEngine;
import com.omnibank.lending.corporate.api.CovenantEngine.BehavioralTestResult;
import com.omnibank.lending.corporate.api.CovenantEngine.BreachSeverity;
import com.omnibank.lending.corporate.api.CovenantEngine.ComplianceSummary;
import com.omnibank.lending.corporate.api.CovenantEngine.CovenantTestResult;
import com.omnibank.lending.corporate.api.CovenantEngine.CurePeriod;
import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduled covenant compliance checker. Runs on a configurable schedule to
 * test all covenants that have reached their next test date. Extracts financial
 * data, compares against thresholds, records results, and triggers notifications
 * for breaches.
 *
 * <p>The checker handles both financial covenants (which require financial data
 * extraction) and behavioral covenants (which require attestation from the
 * relationship manager or automated certificate checks).
 */
public class CovenantComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(CovenantComplianceChecker.class);

    private final CovenantRepository covenantRepository;
    private final CommercialLoanRepository loanRepository;
    private final FinancialDataProvider financialDataProvider;
    private final CovenantNotificationService notificationService;

    public CovenantComplianceChecker(
            CovenantRepository covenantRepository,
            CommercialLoanRepository loanRepository,
            FinancialDataProvider financialDataProvider,
            CovenantNotificationService notificationService
    ) {
        this.covenantRepository = Objects.requireNonNull(covenantRepository);
        this.loanRepository = Objects.requireNonNull(loanRepository);
        this.financialDataProvider = Objects.requireNonNull(financialDataProvider);
        this.notificationService = Objects.requireNonNull(notificationService);
    }

    // ── Dependency interfaces ─────────────────────────────────────────────

    /**
     * Provides financial data for covenant testing. Typically backed by the
     * borrower's latest financial statements or real-time data feeds.
     */
    public interface FinancialDataProvider {
        Optional<CovenantEngine.FinancialData> getFinancialData(UUID borrowerId, LocalDate asOfDate);
    }

    /**
     * Sends notifications when covenant events occur (breaches, upcoming tests,
     * cure period expiration).
     */
    public interface CovenantNotificationService {
        void notifyBreach(UUID loanId, UUID covenantId, BreachSeverity severity, String narrative);
        void notifyUpcomingTest(UUID loanId, UUID covenantId, LocalDate testDate, int daysUntilTest);
        void notifyCurePeriodExpiring(UUID loanId, UUID covenantId, LocalDate cureDeadline, int daysRemaining);
        void notifyComplianceSummary(UUID loanId, ComplianceSummary summary);
    }

    // ── Scheduled test execution ──────────────────────────────────────────

    /**
     * Runs daily at 6:00 AM to check all covenants that have reached their
     * test date. Each covenant is tested individually so a failure in one
     * does not prevent testing of others.
     */
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void runScheduledCovenantTests() {
        LocalDate today = LocalDate.now();
        log.info("Starting scheduled covenant compliance check for date: {}", today);

        List<CovenantEntity> dueCovenants = covenantRepository
                .findByNextTestDateLessThanEqualAndActiveTrue(today);

        log.info("Found {} covenants due for testing", dueCovenants.size());

        int tested = 0;
        int breached = 0;
        int errors = 0;

        for (CovenantEntity covenant : dueCovenants) {
            try {
                if (covenant.isWaived() && !covenant.isWaiverExpired(today)) {
                    log.debug("Skipping waived covenant {} for loan {}", covenant.id(), covenant.loanId());
                    covenant.advanceTestDate();
                    continue;
                }

                if (covenant.isWaiverExpired(today)) {
                    covenant.revokeWaiver();
                    log.info("Waiver expired for covenant {} on loan {}", covenant.id(), covenant.loanId());
                }

                boolean hasBreach = testCovenant(covenant, today);
                tested++;
                if (hasBreach) breached++;

            } catch (Exception e) {
                errors++;
                log.error("Error testing covenant {} for loan {}: {}",
                        covenant.id(), covenant.loanId(), e.getMessage(), e);
            }
        }

        log.info("Covenant check complete: tested={}, breached={}, errors={}", tested, breached, errors);
    }

    /**
     * Sends advance notifications for covenants approaching their test date.
     * Runs weekly to give relationship managers time to gather required data.
     */
    @Scheduled(cron = "0 0 7 * * MON")
    @Transactional(readOnly = true)
    public void sendUpcomingTestNotifications() {
        LocalDate today = LocalDate.now();
        LocalDate lookAhead = today.plusDays(30);

        List<CovenantEntity> upcoming = covenantRepository
                .findByNextTestDateBetweenAndActiveTrue(today, lookAhead);

        for (CovenantEntity covenant : upcoming) {
            int daysUntil = (int) today.until(covenant.nextTestDate()).getDays();
            notificationService.notifyUpcomingTest(
                    covenant.loanId(), covenant.id(), covenant.nextTestDate(), daysUntil);
        }

        log.info("Sent {} upcoming covenant test notifications", upcoming.size());
    }

    /**
     * Checks for cure periods that are about to expire without resolution.
     * Sends escalation notifications 5 days before deadline.
     */
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void checkCurePeriodExpirations() {
        LocalDate today = LocalDate.now();
        LocalDate warningDate = today.plusDays(5);

        List<CovenantEntity> withOpenBreaches = covenantRepository
                .findByActiveTrue().stream()
                .filter(CovenantEntity::hasUnresolvedBreach)
                .toList();

        for (CovenantEntity covenant : withOpenBreaches) {
            for (var breach : covenant.breachHistory()) {
                if (breach.status() == CovenantEntity.CovenantBreachEntity.BreachStatus.OPEN
                        && breach.cureDeadline() != null
                        && !breach.cureDeadline().isAfter(warningDate)) {
                    int daysRemaining = (int) today.until(breach.cureDeadline()).getDays();
                    notificationService.notifyCurePeriodExpiring(
                            covenant.loanId(), covenant.id(),
                            breach.cureDeadline(), Math.max(0, daysRemaining));
                }
            }
        }
    }

    // ── Individual covenant testing ───────────────────────────────────────

    /**
     * Tests a single covenant and records the result. Returns true if a
     * breach was detected.
     */
    @Transactional
    public boolean testCovenant(CovenantEntity covenant, LocalDate testDate) {
        return switch (covenant.covenantType()) {
            case FINANCIAL -> testFinancialCovenant(covenant, testDate);
            case BEHAVIORAL -> testBehavioralCovenant(covenant, testDate);
        };
    }

    private boolean testFinancialCovenant(CovenantEntity covenant, LocalDate testDate) {
        CommercialLoanEntity loan = loanRepository.findById(covenant.loanId())
                .orElseThrow(() -> new IllegalStateException(
                        "Loan not found for covenant: " + covenant.loanId()));

        Optional<CovenantEngine.FinancialData> financialData =
                financialDataProvider.getFinancialData(loan.borrower(), testDate);

        if (financialData.isEmpty()) {
            log.warn("No financial data available for borrower {} as of {}; marking covenant {} as untestable",
                    loan.borrower(), testDate, covenant.id());
            recordTestResult(covenant, testDate, false, null, covenant.thresholdValue(),
                    "Unable to test: financial data not available for " + testDate);
            return false;
        }

        var financialCovenant = new Covenant.Financial(
                covenant.id().toString(),
                covenant.metric(),
                covenant.operator(),
                covenant.thresholdValue(),
                covenant.nextTestDate()
        );

        CurePeriod curePeriod = new CurePeriod(covenant.curePeriodDays());
        Optional<LocalDate> priorBreachDate = covenant.hasUnresolvedBreach()
                ? covenant.breachHistory().stream()
                        .filter(b -> b.status() == CovenantEntity.CovenantBreachEntity.BreachStatus.OPEN)
                        .map(CovenantEntity.CovenantBreachEntity::breachDate)
                        .findFirst()
                : Optional.empty();

        CovenantTestResult result = CovenantEngine.testFinancialCovenant(
                financialCovenant, financialData.get(), curePeriod, priorBreachDate);

        recordTestResult(covenant, testDate, result.passed(),
                result.actualValue(), result.thresholdValue(), result.narrative());

        if (!result.passed()) {
            handleBreach(covenant, testDate, result.severity(), result.narrative(), curePeriod);
        }

        covenant.advanceTestDate();
        return !result.passed();
    }

    private boolean testBehavioralCovenant(CovenantEntity covenant, LocalDate testDate) {
        // Behavioral covenants require manual attestation; if no attestation
        // exists, assume non-compliance and flag for review.
        log.info("Behavioral covenant {} on loan {} requires manual attestation",
                covenant.id(), covenant.loanId());

        recordTestResult(covenant, testDate, false, null, null,
                "Behavioral covenant '%s' pending manual attestation".formatted(covenant.description()));

        covenant.advanceTestDate();
        return false; // Not counted as breach until RM confirms non-compliance
    }

    // ── Result recording ──────────────────────────────────────────────────

    private void recordTestResult(CovenantEntity covenant, LocalDate testDate,
                                  boolean passed, BigDecimal actualValue,
                                  BigDecimal thresholdValue, String narrative) {
        var resultEntity = new CovenantEntity.CovenantTestResultEntity(
                UUID.randomUUID(), covenant.id(), testDate,
                passed, actualValue, thresholdValue, narrative
        );
        covenant.recordTestResult(resultEntity);
        log.debug("Recorded test result for covenant {}: passed={}", covenant.id(), passed);
    }

    private void handleBreach(CovenantEntity covenant, LocalDate breachDate,
                              BreachSeverity severity, String narrative,
                              CurePeriod curePeriod) {
        LocalDate cureDeadline = curePeriod.days() > 0
                ? curePeriod.deadlineFrom(breachDate)
                : null;

        var breach = new CovenantEntity.CovenantBreachEntity(
                UUID.randomUUID(), covenant.id(), breachDate, cureDeadline);
        covenant.recordBreach(breach);

        notificationService.notifyBreach(
                covenant.loanId(), covenant.id(), severity, narrative);

        log.warn("Covenant breach detected: covenant={}, loan={}, severity={}, cure deadline={}",
                covenant.id(), covenant.loanId(), severity, cureDeadline);

        if (covenant.consecutiveBreachCount() >= 3) {
            log.error("ALERT: Covenant {} on loan {} has {} consecutive breaches",
                    covenant.id(), covenant.loanId(), covenant.consecutiveBreachCount());
        }
    }

    // ── On-demand compliance summary ──────────────────────────────────────

    /**
     * Generates a full compliance summary for a specific loan on demand,
     * without advancing test dates. Used by relationship managers for reviews.
     */
    @Transactional(readOnly = true)
    public ComplianceSummary generateComplianceSummary(UUID loanId, LocalDate asOfDate) {
        List<CovenantEntity> covenants = covenantRepository.findByLoanIdAndActiveTrue(loanId);

        CommercialLoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan: " + loanId));

        Optional<CovenantEngine.FinancialData> financialData =
                financialDataProvider.getFinancialData(loan.borrower(), asOfDate);

        List<CovenantTestResult> financialResults = new ArrayList<>();
        List<BehavioralTestResult> behavioralResults = new ArrayList<>();

        for (CovenantEntity covenant : covenants) {
            if (covenant.covenantType() == CovenantEntity.CovenantType.FINANCIAL && financialData.isPresent()) {
                var fc = new Covenant.Financial(
                        covenant.id().toString(), covenant.metric(),
                        covenant.operator(), covenant.thresholdValue(), covenant.nextTestDate());
                financialResults.add(CovenantEngine.testFinancialCovenant(
                        fc, financialData.get(), new CurePeriod(covenant.curePeriodDays()), Optional.empty()));
            } else if (covenant.covenantType() == CovenantEntity.CovenantType.BEHAVIORAL) {
                behavioralResults.add(new BehavioralTestResult(
                        covenant.id().toString(), !covenant.hasUnresolvedBreach(),
                        covenant.hasUnresolvedBreach() ? BreachSeverity.TECHNICAL : BreachSeverity.NONE,
                        asOfDate, Optional.empty(), covenant.description()));
            }
        }

        LoanId lid = new LoanId(loanId);
        ComplianceSummary summary = CovenantEngine.summarize(lid, financialResults, behavioralResults, asOfDate);
        notificationService.notifyComplianceSummary(loanId, summary);
        return summary;
    }
}
