package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.DayCountConvention;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily interest calculation engine for savings and CD accounts. Computes
 * accrued interest using the appropriate day-count convention, applies tiered
 * rates for high-yield savings, handles compounding periods, and performs
 * withholding for backup tax obligations. Accrual posting occurs nightly
 * via a scheduled batch, with actual credit to the customer on the
 * configured compounding boundary (daily, monthly, quarterly).
 *
 * <p>Tier breakpoints and withholding rates are configured per-product.
 * A real bank would pull these from a product master; Omnibank keeps the
 * table in code because the product set is small and stable.
 */
public class InterestCalculationEngine {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationEngine.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final BigDecimal BACKUP_WITHHOLDING_RATE = new BigDecimal("0.24");

    /** Tiered rate breakpoints for high-yield savings. Balances above each tier earn the tier rate on that slice. */
    private static final List<TierBreakpoint> HIGH_YIELD_TIERS = List.of(
            new TierBreakpoint(Money.of("10000.00", CurrencyCode.USD), Percent.ofBps(425)),
            new TierBreakpoint(Money.of("50000.00", CurrencyCode.USD), Percent.ofBps(440)),
            new TierBreakpoint(Money.of("100000.00", CurrencyCode.USD), Percent.ofBps(460)),
            new TierBreakpoint(Money.of("250000.00", CurrencyCode.USD), Percent.ofBps(475))
    );

    /** Compounding periods by product kind. Savings compound daily; CDs compound monthly. */
    private static final Map<ConsumerProduct.Kind, CompoundingPeriod> COMPOUNDING = Map.of(
            ConsumerProduct.Kind.SAVINGS, CompoundingPeriod.DAILY,
            ConsumerProduct.Kind.CD, CompoundingPeriod.MONTHLY
    );

    private record TierBreakpoint(Money ceiling, Percent rate) {}

    enum CompoundingPeriod {
        DAILY, MONTHLY, QUARTERLY;

        boolean isPostingBoundary(LocalDate date) {
            return switch (this) {
                case DAILY -> true;
                case MONTHLY -> date.getDayOfMonth() == 1;
                case QUARTERLY -> date.getDayOfMonth() == 1
                        && (date.getMonthValue() - 1) % 3 == 0;
            };
        }
    }

    record AccrualResult(
            String accountNumber,
            Money grossInterest,
            Money withholdingAmount,
            Money netInterest,
            LocalDate accrualDate,
            boolean posted
    ) {}

    private final ConsumerAccountRepository accounts;
    private final LedgerQueries ledger;
    private final PostingService posting;
    private final Clock clock;

    public InterestCalculationEngine(ConsumerAccountRepository accounts,
                                     LedgerQueries ledger,
                                     PostingService posting,
                                     Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
        this.clock = clock;
    }

    /**
     * Nightly batch: calculate and accrue interest for every eligible account.
     * Runs at 02:00 ET on business days.
     */
    @Scheduled(cron = "0 0 2 * * MON-FRI", zone = "America/New_York")
    @Transactional
    public List<AccrualResult> runDailyAccrual() {
        LocalDate today = LocalDate.now(clock);
        LocalDate priorDay = today.minusDays(1);
        log.info("Starting daily interest accrual for {}", priorDay);

        List<ConsumerAccountEntity> eligible = accounts.findAll().stream()
                .filter(a -> a.status() == com.omnibank.accounts.consumer.api.AccountStatus.OPEN)
                .filter(a -> a.product().kind != ConsumerProduct.Kind.CHECKING)
                .toList();

        List<AccrualResult> results = new ArrayList<>();
        for (ConsumerAccountEntity account : eligible) {
            try {
                AccrualResult result = calculateAndAccrue(account, priorDay, today);
                results.add(result);
            } catch (Exception e) {
                log.error("Interest accrual failed for account {}: {}",
                        account.accountNumber(), e.getMessage(), e);
            }
        }

        log.info("Daily accrual complete: {} accounts processed, {} accruals posted",
                eligible.size(), results.stream().filter(AccrualResult::posted).count());
        return results;
    }

    /**
     * Calculate interest for a single account for one day. Uses tiered rates
     * for high-yield savings, flat rate for standard savings and CDs.
     */
    AccrualResult calculateAndAccrue(ConsumerAccountEntity account, LocalDate accrualDate, LocalDate today) {
        GlAccountCode liabilityGl = LedgerMapping.depositLiability(account.product());
        Money balance = ledger.balanceAsOf(liabilityGl, accrualDate);

        if (balance.isZero() || balance.isNegative()) {
            return new AccrualResult(account.accountNumber(), Money.zero(account.currency()),
                    Money.zero(account.currency()), Money.zero(account.currency()), accrualDate, false);
        }

        DayCountConvention dayCount = resolveDayCount(account.product());
        BigDecimal yearFraction = dayCount.yearFraction(accrualDate, accrualDate.plusDays(1), MC);

        Money grossInterest = computeTieredInterest(account, balance, yearFraction);
        Money withheld = computeWithholding(account, grossInterest);
        Money netInterest = grossInterest.minus(withheld);

        CompoundingPeriod period = COMPOUNDING.getOrDefault(
                account.product().kind, CompoundingPeriod.MONTHLY);

        boolean posted = false;
        if (period.isPostingBoundary(today)) {
            postAccrual(account, netInterest, withheld, accrualDate);
            posted = true;
        }

        return new AccrualResult(account.accountNumber(), grossInterest, withheld,
                netInterest, accrualDate, posted);
    }

    private Money computeTieredInterest(ConsumerAccountEntity account, Money balance,
                                         BigDecimal yearFraction) {
        if (account.product() == ConsumerProduct.SAVINGS_HIGH_YIELD) {
            return computeHighYieldTieredInterest(balance, yearFraction);
        }
        BigDecimal rate = account.product().aprBase.asFraction(MC);
        BigDecimal interest = balance.amount().multiply(rate, MC).multiply(yearFraction, MC);
        return Money.of(interest, account.currency());
    }

    private Money computeHighYieldTieredInterest(Money balance, BigDecimal yearFraction) {
        Money totalInterest = Money.zero(balance.currency());
        Money remaining = balance;
        Money previousCeiling = Money.zero(balance.currency());

        for (TierBreakpoint tier : HIGH_YIELD_TIERS) {
            if (remaining.isZero() || remaining.isNegative()) break;

            Money tierWidth = tier.ceiling().minus(previousCeiling);
            Money applicableBalance = remaining.compareTo(tierWidth) <= 0 ? remaining : tierWidth;

            BigDecimal rate = tier.rate().asFraction(MC);
            BigDecimal interest = applicableBalance.amount().multiply(rate, MC)
                    .multiply(yearFraction, MC);
            totalInterest = totalInterest.plus(Money.of(interest, balance.currency()));

            remaining = remaining.minus(applicableBalance);
            previousCeiling = tier.ceiling();
        }

        // Any balance above the last tier earns the top tier rate
        if (remaining.isPositive() && !HIGH_YIELD_TIERS.isEmpty()) {
            Percent topRate = HIGH_YIELD_TIERS.get(HIGH_YIELD_TIERS.size() - 1).rate();
            BigDecimal rate = topRate.asFraction(MC);
            BigDecimal interest = remaining.amount().multiply(rate, MC)
                    .multiply(yearFraction, MC);
            totalInterest = totalInterest.plus(Money.of(interest, balance.currency()));
        }

        return totalInterest;
    }

    /**
     * Backup withholding: 24% on interest if the customer is subject to
     * IRS backup withholding (missing or invalid TIN). Simplified here
     * by applying to accounts flagged during KYC.
     */
    private Money computeWithholding(ConsumerAccountEntity account, Money grossInterest) {
        if (isSubjectToBackupWithholding(account)) {
            BigDecimal withheld = grossInterest.amount()
                    .multiply(BACKUP_WITHHOLDING_RATE, MC);
            return Money.of(withheld, account.currency());
        }
        return Money.zero(account.currency());
    }

    private boolean isSubjectToBackupWithholding(ConsumerAccountEntity account) {
        // In production, this queries the customer tax certification table.
        // Simplified: no accounts are currently subject to backup withholding.
        return false;
    }

    private void postAccrual(ConsumerAccountEntity account, Money netInterest,
                              Money withheld, LocalDate accrualDate) {
        if (netInterest.isZero() && withheld.isZero()) return;

        GlAccountCode liabilityGl = LedgerMapping.depositLiability(account.product());
        List<PostingLine> lines = new ArrayList<>();

        if (netInterest.isPositive()) {
            lines.add(PostingLine.debit(LedgerMapping.INTEREST_EXPENSE, netInterest,
                    "interest accrual " + accrualDate));
            lines.add(PostingLine.credit(liabilityGl, netInterest,
                    "interest credit to customer"));
        }

        if (withheld.isPositive()) {
            GlAccountCode withholdingPayable = new GlAccountCode("LIA-2200-001");
            lines.add(PostingLine.debit(LedgerMapping.INTEREST_EXPENSE, withheld,
                    "interest withholding " + accrualDate));
            lines.add(PostingLine.credit(withholdingPayable, withheld,
                    "backup withholding payable to IRS"));
        }

        if (lines.size() >= 2) {
            JournalEntry je = new JournalEntry(
                    UUID.randomUUID(), accrualDate,
                    "INT-ACCR-" + account.accountNumber() + "-" + accrualDate,
                    "Daily interest accrual for " + account.product(),
                    lines
            );
            posting.post(je);
            log.debug("Posted interest accrual for {}: net={}, withheld={}",
                    account.accountNumber(), netInterest, withheld);
        }
    }

    private DayCountConvention resolveDayCount(ConsumerProduct product) {
        return switch (product.kind) {
            case SAVINGS -> DayCountConvention.ACTUAL_365;
            case CD -> DayCountConvention.ACTUAL_360;
            case CHECKING -> DayCountConvention.ACTUAL_365; // checking shouldn't reach here
        };
    }
}
