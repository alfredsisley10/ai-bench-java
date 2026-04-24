package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import com.omnibank.shared.domain.Tenor;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CD maturity processing engine. Handles the end-of-term lifecycle for
 * certificates of deposit:
 *
 * <ul>
 *   <li>Maturity detection: identifies CDs reaching maturity today</li>
 *   <li>Grace period: 10-day window after maturity for customer action</li>
 *   <li>Auto-renewal: renews into the same product at current rates if no
 *       customer instruction received during grace period</li>
 *   <li>Rate reset: applies the current product rate (not the original rate)
 *       on renewal</li>
 *   <li>Early withdrawal: calculates penalty (typically 3-6 months interest)
 *       and processes the break</li>
 *   <li>Notification: publishes events for customer notification service</li>
 * </ul>
 *
 * <p>Runs as a daily batch. CDs that mature on weekends or holidays are
 * processed on the next business day.
 */
public class CdMaturityProcessor {

    private static final Logger log = LoggerFactory.getLogger(CdMaturityProcessor.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    /** Grace period after maturity during which the customer can instruct. */
    private static final int GRACE_PERIOD_DAYS = 10;

    /** Early withdrawal penalty schedule: months of interest forfeited by term. */
    private static final int PENALTY_MONTHS_SHORT_TERM = 3;  // <= 12M
    private static final int PENALTY_MONTHS_LONG_TERM = 6;   // > 12M

    /** GL code for penalty revenue. */
    private static final GlAccountCode EARLY_WITHDRAWAL_PENALTY_REVENUE =
            new GlAccountCode("REV-4300-001");

    sealed interface MaturityAction permits
            MaturityAction.AutoRenewal,
            MaturityAction.GracePeriodStarted,
            MaturityAction.EarlyWithdrawal,
            MaturityAction.PendingCustomerInstruction {

        record AutoRenewal(AccountNumber account, ConsumerProduct renewedProduct,
                           Percent newRate, LocalDate newMaturityDate,
                           Money principalRenewed) implements MaturityAction {}

        record GracePeriodStarted(AccountNumber account, LocalDate maturityDate,
                                   LocalDate graceExpiry) implements MaturityAction {}

        record EarlyWithdrawal(AccountNumber account, Money principal,
                                Money accruedInterest, Money penalty,
                                Money netProceeds) implements MaturityAction {}

        record PendingCustomerInstruction(AccountNumber account,
                                           LocalDate maturityDate,
                                           int daysRemaining) implements MaturityAction {}
    }

    record CdMaturityEvent(UUID eventId, Instant occurredAt, AccountNumber account,
                           String action, LocalDate maturityDate) implements DomainEvent {
        @Override
        public String eventType() {
            return "accounts.consumer.cd_maturity";
        }
    }

    private final ConsumerAccountRepository accounts;
    private final LedgerQueries ledger;
    private final PostingService posting;
    private final EventBus events;
    private final Clock clock;

    public CdMaturityProcessor(ConsumerAccountRepository accounts,
                               LedgerQueries ledger,
                               PostingService posting,
                               EventBus events,
                               Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Daily batch: process all CDs that have reached or passed maturity.
     * Runs at 06:00 ET on business days after interest accrual has completed.
     */
    @Scheduled(cron = "0 0 6 * * MON-FRI", zone = "America/New_York")
    @Transactional
    public List<MaturityAction> runDailyMaturityProcessing() {
        LocalDate today = LocalDate.now(clock);
        log.info("Starting CD maturity processing for {}", today);

        List<ConsumerAccountEntity> maturingCds = accounts.findAll().stream()
                .filter(a -> a.product().isMaturing())
                .filter(a -> a.status() == AccountStatus.OPEN)
                .filter(a -> a.maturesOn() != null && !a.maturesOn().isAfter(today))
                .toList();

        List<MaturityAction> actions = new ArrayList<>();
        for (ConsumerAccountEntity cd : maturingCds) {
            try {
                MaturityAction action = processMaturity(cd, today);
                actions.add(action);
            } catch (Exception e) {
                log.error("CD maturity processing failed for account {}: {}",
                        cd.accountNumber(), e.getMessage(), e);
            }
        }

        log.info("CD maturity processing complete: {} CDs evaluated", maturingCds.size());
        return actions;
    }

    /**
     * Process a single CD maturity. Determines the appropriate action based
     * on maturity date and grace period status.
     */
    MaturityAction processMaturity(ConsumerAccountEntity cd, LocalDate today) {
        LocalDate maturityDate = cd.maturesOn();
        LocalDate graceExpiry = maturityDate.plusDays(GRACE_PERIOD_DAYS);
        AccountNumber accountNumber = AccountNumber.of(cd.accountNumber());

        if (today.equals(maturityDate)) {
            // Maturity day: start grace period, notify customer
            Instant now = Timestamp.now(clock);
            publishMaturityEvent(accountNumber, "grace_period_started", maturityDate, now);
            log.info("CD {} matured on {}. Grace period until {}",
                    cd.accountNumber(), maturityDate, graceExpiry);
            return new MaturityAction.GracePeriodStarted(accountNumber, maturityDate, graceExpiry);
        }

        if (today.isBefore(graceExpiry)) {
            // Within grace period: pending customer instruction
            int daysRemaining = (int) ChronoUnit.DAYS.between(today, graceExpiry);
            return new MaturityAction.PendingCustomerInstruction(
                    accountNumber, maturityDate, daysRemaining);
        }

        // Grace period expired: auto-renew at current rates
        return autoRenew(cd, today);
    }

    /**
     * Auto-renew a CD into the same product at the current rate. The principal
     * plus accrued interest rolls into the new term.
     */
    private MaturityAction.AutoRenewal autoRenew(ConsumerAccountEntity cd, LocalDate today) {
        AccountNumber accountNumber = AccountNumber.of(cd.accountNumber());
        ConsumerProduct product = cd.product();
        Percent currentRate = product.aprBase;
        Tenor term = product.term;

        GlAccountCode liabilityGl = LedgerMapping.depositLiability(product);
        Money currentBalance = ledger.currentBalance(liabilityGl);

        LocalDate newMaturityDate = term.applyTo(today);

        // In a real implementation, the entity would be updated with the new maturity
        // and the rate reset would be recorded. The posting engine would handle the
        // rollover journal entry.
        Instant now = Timestamp.now(clock);
        publishMaturityEvent(accountNumber, "auto_renewed", cd.maturesOn(), now);

        log.info("CD {} auto-renewed: product={}, rate={}, new maturity={}, principal={}",
                cd.accountNumber(), product, currentRate, newMaturityDate, currentBalance);

        return new MaturityAction.AutoRenewal(accountNumber, product, currentRate,
                newMaturityDate, currentBalance);
    }

    /**
     * Process early withdrawal of a CD before maturity. Calculates the penalty
     * based on the original term and posts the penalty debit.
     *
     * @return the early withdrawal action with penalty details
     */
    @Transactional
    public MaturityAction.EarlyWithdrawal processEarlyWithdrawal(AccountNumber accountNumber,
                                                                   String reason) {
        ConsumerAccountEntity cd = accounts.findById(accountNumber.raw())
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountNumber));

        if (!cd.product().isMaturing()) {
            throw new IllegalStateException("Early withdrawal only applies to CD accounts");
        }
        if (cd.status() != AccountStatus.OPEN) {
            throw new IllegalStateException("Account must be OPEN for early withdrawal");
        }

        GlAccountCode liabilityGl = LedgerMapping.depositLiability(cd.product());
        Money principal = ledger.currentBalance(liabilityGl);
        Money accruedInterest = calculateAccruedInterest(cd, principal);
        Money penalty = calculateEarlyWithdrawalPenalty(cd, principal);
        Money netProceeds = principal.plus(accruedInterest).minus(penalty);

        if (netProceeds.isNegative()) {
            // Penalty cannot exceed principal + accrued interest
            penalty = principal.plus(accruedInterest);
            netProceeds = Money.zero(cd.currency());
        }

        postEarlyWithdrawalPenalty(cd, penalty);

        Instant now = Timestamp.now(clock);
        publishMaturityEvent(accountNumber, "early_withdrawal", cd.maturesOn(), now);

        log.info("CD {} early withdrawal: principal={}, accrued={}, penalty={}, net={}",
                cd.accountNumber(), principal, accruedInterest, penalty, netProceeds);

        return new MaturityAction.EarlyWithdrawal(accountNumber, principal,
                accruedInterest, penalty, netProceeds);
    }

    /**
     * Calculate early withdrawal penalty. Short-term CDs (<=12M) forfeit
     * 3 months of interest; long-term CDs (>12M) forfeit 6 months.
     */
    Money calculateEarlyWithdrawalPenalty(ConsumerAccountEntity cd, Money principal) {
        int penaltyMonths = isLongTermCd(cd.product())
                ? PENALTY_MONTHS_LONG_TERM
                : PENALTY_MONTHS_SHORT_TERM;

        BigDecimal annualRate = cd.product().aprBase.asFraction(MC);
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(12), MC);
        BigDecimal penaltyAmount = principal.amount()
                .multiply(monthlyRate, MC)
                .multiply(BigDecimal.valueOf(penaltyMonths), MC);

        return Money.of(penaltyAmount, cd.currency());
    }

    private Money calculateAccruedInterest(ConsumerAccountEntity cd, Money principal) {
        LocalDate today = LocalDate.now(clock);
        LocalDate openDate = cd.openedOn();
        long daysHeld = ChronoUnit.DAYS.between(openDate, today);

        BigDecimal annualRate = cd.product().aprBase.asFraction(MC);
        BigDecimal dailyRate = annualRate.divide(BigDecimal.valueOf(360), MC);
        BigDecimal accrued = principal.amount()
                .multiply(dailyRate, MC)
                .multiply(BigDecimal.valueOf(daysHeld), MC);

        return Money.of(accrued, cd.currency());
    }

    private void postEarlyWithdrawalPenalty(ConsumerAccountEntity cd, Money penalty) {
        if (penalty.isZero()) return;

        GlAccountCode liabilityGl = LedgerMapping.depositLiability(cd.product());
        JournalEntry je = new JournalEntry(
                UUID.randomUUID(), LocalDate.now(clock),
                "CD-PENALTY-" + cd.accountNumber() + "-" + LocalDate.now(clock),
                "Early withdrawal penalty for CD " + cd.product(),
                List.of(
                        PostingLine.debit(liabilityGl, penalty, "early withdrawal penalty debit"),
                        PostingLine.credit(EARLY_WITHDRAWAL_PENALTY_REVENUE, penalty,
                                "early withdrawal penalty revenue")
                )
        );
        posting.post(je);
    }

    private boolean isLongTermCd(ConsumerProduct product) {
        return switch (product) {
            case CD_60M -> true;
            case CD_6M, CD_12M -> false;
            default -> false;
        };
    }

    /**
     * Identify CDs approaching maturity within the next N days.
     * Used by the notification service to send advance maturity notices.
     */
    public List<AccountNumber> findCdsApproachingMaturity(int withinDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate horizon = today.plusDays(withinDays);

        return accounts.findAll().stream()
                .filter(a -> a.product().isMaturing())
                .filter(a -> a.status() == AccountStatus.OPEN)
                .filter(a -> a.maturesOn() != null)
                .filter(a -> !a.maturesOn().isBefore(today) && !a.maturesOn().isAfter(horizon))
                .map(a -> AccountNumber.of(a.accountNumber()))
                .toList();
    }

    private void publishMaturityEvent(AccountNumber account, String action,
                                       LocalDate maturityDate, Instant when) {
        events.publish(new CdMaturityEvent(UUID.randomUUID(), when, account,
                action, maturityDate));
    }
}
