package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Account fee engine for consumer deposit accounts. Handles the full spectrum
 * of recurring and event-driven fees:
 *
 * <ul>
 *   <li><b>Monthly maintenance fees:</b> Flat fees per product, waivable by
 *       maintaining a minimum daily balance or by relationship tier.</li>
 *   <li><b>Minimum balance fees:</b> Assessed when the average daily balance
 *       drops below the product minimum during the statement cycle.</li>
 *   <li><b>Paper statement fees:</b> Charged to accounts not enrolled in
 *       electronic statements.</li>
 *   <li><b>Relationship tier waivers:</b> Customers with combined balances
 *       above tier thresholds receive automatic fee waivers.</li>
 *   <li><b>Fee reversals:</b> Goodwill or error-correction reversals with
 *       mandatory audit trail and approval tracking.</li>
 * </ul>
 *
 * <p>Monthly fees are assessed on the first business day of each statement
 * cycle. The engine evaluates waiver eligibility before posting. Fee revenue
 * is recognized immediately upon posting.
 */
public class AccountFeeEngine {

    private static final Logger log = LoggerFactory.getLogger(AccountFeeEngine.class);

    /** GL codes for fee-related postings. */
    private static final GlAccountCode FEE_REVENUE = new GlAccountCode("REV-4100-001");
    private static final GlAccountCode PAPER_STATEMENT_REVENUE = new GlAccountCode("REV-4100-002");

    /** Fee schedule by product. */
    private static final Map<ConsumerProduct, FeeSchedule> FEE_SCHEDULES;

    static {
        FEE_SCHEDULES = new EnumMap<>(ConsumerProduct.class);
        FEE_SCHEDULES.put(ConsumerProduct.CHECKING_BASIC, new FeeSchedule(
                Money.of("12.00", CurrencyCode.USD),
                Money.of("1500.00", CurrencyCode.USD),
                Money.of("500.00", CurrencyCode.USD),
                Money.of("2.00", CurrencyCode.USD)
        ));
        FEE_SCHEDULES.put(ConsumerProduct.CHECKING_PREMIUM, new FeeSchedule(
                Money.of("25.00", CurrencyCode.USD),
                Money.of("15000.00", CurrencyCode.USD),
                Money.of("5000.00", CurrencyCode.USD),
                Money.of("0.00", CurrencyCode.USD)
        ));
        FEE_SCHEDULES.put(ConsumerProduct.SAVINGS_STANDARD, new FeeSchedule(
                Money.of("5.00", CurrencyCode.USD),
                Money.of("300.00", CurrencyCode.USD),
                Money.of("200.00", CurrencyCode.USD),
                Money.of("2.00", CurrencyCode.USD)
        ));
        FEE_SCHEDULES.put(ConsumerProduct.SAVINGS_HIGH_YIELD, new FeeSchedule(
                Money.of("0.00", CurrencyCode.USD),
                Money.of("0.00", CurrencyCode.USD),
                Money.of("0.00", CurrencyCode.USD),
                Money.of("0.00", CurrencyCode.USD)
        ));
    }

    /** Relationship tier thresholds for fee waivers. */
    private static final List<RelationshipTier> RELATIONSHIP_TIERS = List.of(
            new RelationshipTier("PLATINUM", Money.of("100000.00", CurrencyCode.USD), true, true, true),
            new RelationshipTier("GOLD", Money.of("25000.00", CurrencyCode.USD), true, true, false),
            new RelationshipTier("SILVER", Money.of("10000.00", CurrencyCode.USD), true, false, false),
            new RelationshipTier("STANDARD", Money.of("0.00", CurrencyCode.USD), false, false, false)
    );

    record FeeSchedule(Money monthlyMaintenanceFee, Money maintenanceFeeWaiverBalance,
                        Money minimumBalanceThreshold, Money paperStatementFee) {}

    record RelationshipTier(String name, Money minimumCombinedBalance,
                            boolean waivesMaintenanceFee, boolean waivesMinimumBalanceFee,
                            boolean waivesPaperStatementFee) {}

    sealed interface FeeAction permits
            FeeAction.FeeAssessed,
            FeeAction.FeeWaived,
            FeeAction.FeeReversed,
            FeeAction.NoFeeApplicable {

        record FeeAssessed(AccountNumber account, String feeType, Money amount,
                           LocalDate assessedOn) implements FeeAction {}
        record FeeWaived(AccountNumber account, String feeType, Money amount,
                         String waiverReason) implements FeeAction {}
        record FeeReversed(AccountNumber account, String feeType, Money amount,
                           String reversalReason, String approvedBy) implements FeeAction {}
        record NoFeeApplicable(AccountNumber account, String feeType) implements FeeAction {}
    }

    record FeeEvent(UUID eventId, Instant occurredAt, AccountNumber account,
                    String feeType, Money amount, String action) implements DomainEvent {
        @Override
        public String eventType() {
            return "accounts.consumer.fee_assessed";
        }
    }

    private final ConsumerAccountRepository accounts;
    private final LedgerQueries ledger;
    private final PostingService posting;
    private final EventBus events;
    private final Clock clock;

    public AccountFeeEngine(ConsumerAccountRepository accounts,
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
     * Monthly fee assessment batch. Runs on the 1st of each month at 04:00 ET.
     * Evaluates every open account for applicable fees and waiver eligibility.
     */
    @Scheduled(cron = "0 0 4 1 * *", zone = "America/New_York")
    @Transactional
    public List<FeeAction> runMonthlyFeeAssessment() {
        LocalDate today = LocalDate.now(clock);
        log.info("Starting monthly fee assessment for cycle ending {}", today);

        List<ConsumerAccountEntity> openAccounts = accounts.findAll().stream()
                .filter(a -> a.status() == AccountStatus.OPEN)
                .filter(a -> !a.product().isMaturing()) // CDs don't have maintenance fees
                .toList();

        List<FeeAction> actions = new ArrayList<>();
        for (ConsumerAccountEntity account : openAccounts) {
            try {
                actions.addAll(assessFeesForAccount(account, today));
            } catch (Exception e) {
                log.error("Fee assessment failed for account {}: {}",
                        account.accountNumber(), e.getMessage(), e);
            }
        }

        long assessed = actions.stream()
                .filter(a -> a instanceof FeeAction.FeeAssessed).count();
        long waived = actions.stream()
                .filter(a -> a instanceof FeeAction.FeeWaived).count();
        log.info("Monthly fee assessment complete: {} fees assessed, {} fees waived",
                assessed, waived);
        return actions;
    }

    /**
     * Assess all applicable fees for a single account.
     */
    List<FeeAction> assessFeesForAccount(ConsumerAccountEntity account, LocalDate cycleDate) {
        List<FeeAction> actions = new ArrayList<>();
        AccountNumber accountNumber = AccountNumber.of(account.accountNumber());
        FeeSchedule schedule = FEE_SCHEDULES.get(account.product());

        if (schedule == null) {
            actions.add(new FeeAction.NoFeeApplicable(accountNumber, "ALL"));
            return actions;
        }

        RelationshipTier tier = resolveRelationshipTier(account);
        GlAccountCode liabilityGl = LedgerMapping.depositLiability(account.product());
        Money currentBalance = ledger.currentBalance(liabilityGl);

        // Monthly maintenance fee
        actions.add(assessMaintenanceFee(accountNumber, account, schedule,
                tier, currentBalance, cycleDate));

        // Minimum balance fee
        actions.add(assessMinimumBalanceFee(accountNumber, account, schedule,
                tier, currentBalance, cycleDate));

        // Paper statement fee
        actions.add(assessPaperStatementFee(accountNumber, account, schedule,
                tier, cycleDate));

        return actions;
    }

    private FeeAction assessMaintenanceFee(AccountNumber account,
                                            ConsumerAccountEntity entity,
                                            FeeSchedule schedule,
                                            RelationshipTier tier,
                                            Money currentBalance,
                                            LocalDate cycleDate) {
        Money fee = schedule.monthlyMaintenanceFee();
        if (fee.isZero()) {
            return new FeeAction.NoFeeApplicable(account, "MONTHLY_MAINTENANCE");
        }

        // Waiver check 1: relationship tier
        if (tier.waivesMaintenanceFee()) {
            return new FeeAction.FeeWaived(account, "MONTHLY_MAINTENANCE", fee,
                    "Relationship tier waiver: " + tier.name());
        }

        // Waiver check 2: minimum balance maintained
        if (currentBalance.compareTo(schedule.maintenanceFeeWaiverBalance()) >= 0) {
            return new FeeAction.FeeWaived(account, "MONTHLY_MAINTENANCE", fee,
                    "Minimum balance waiver: balance " + currentBalance
                            + " >= " + schedule.maintenanceFeeWaiverBalance());
        }

        // No waiver: assess the fee
        postFee(entity, fee, "Monthly maintenance fee", FEE_REVENUE, cycleDate);
        publishFeeEvent(account, "MONTHLY_MAINTENANCE", fee, "assessed");
        return new FeeAction.FeeAssessed(account, "MONTHLY_MAINTENANCE", fee, cycleDate);
    }

    private FeeAction assessMinimumBalanceFee(AccountNumber account,
                                               ConsumerAccountEntity entity,
                                               FeeSchedule schedule,
                                               RelationshipTier tier,
                                               Money currentBalance,
                                               LocalDate cycleDate) {
        Money threshold = schedule.minimumBalanceThreshold();
        if (threshold.isZero()) {
            return new FeeAction.NoFeeApplicable(account, "MINIMUM_BALANCE");
        }

        if (currentBalance.compareTo(threshold) >= 0) {
            return new FeeAction.NoFeeApplicable(account, "MINIMUM_BALANCE");
        }

        if (tier.waivesMinimumBalanceFee()) {
            Money feeAmount = Money.of("5.00", entity.currency());
            return new FeeAction.FeeWaived(account, "MINIMUM_BALANCE", feeAmount,
                    "Relationship tier waiver: " + tier.name());
        }

        Money feeAmount = Money.of("5.00", entity.currency());
        postFee(entity, feeAmount, "Below minimum balance fee", FEE_REVENUE, cycleDate);
        publishFeeEvent(account, "MINIMUM_BALANCE", feeAmount, "assessed");
        return new FeeAction.FeeAssessed(account, "MINIMUM_BALANCE", feeAmount, cycleDate);
    }

    private FeeAction assessPaperStatementFee(AccountNumber account,
                                               ConsumerAccountEntity entity,
                                               FeeSchedule schedule,
                                               RelationshipTier tier,
                                               LocalDate cycleDate) {
        Money fee = schedule.paperStatementFee();
        if (fee.isZero()) {
            return new FeeAction.NoFeeApplicable(account, "PAPER_STATEMENT");
        }

        if (isEnrolledInEStatements(entity)) {
            return new FeeAction.NoFeeApplicable(account, "PAPER_STATEMENT");
        }

        if (tier.waivesPaperStatementFee()) {
            return new FeeAction.FeeWaived(account, "PAPER_STATEMENT", fee,
                    "Relationship tier waiver: " + tier.name());
        }

        postFee(entity, fee, "Paper statement fee", PAPER_STATEMENT_REVENUE, cycleDate);
        publishFeeEvent(account, "PAPER_STATEMENT", fee, "assessed");
        return new FeeAction.FeeAssessed(account, "PAPER_STATEMENT", fee, cycleDate);
    }

    /**
     * Reverse a previously assessed fee. Requires approval and reason tracking
     * for audit compliance.
     */
    @Transactional
    public FeeAction.FeeReversed reverseFee(AccountNumber accountNumber, String feeType,
                                             Money amount, String reason, String approvedBy) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(approvedBy, "approvedBy");

        ConsumerAccountEntity entity = accounts.findById(accountNumber.raw())
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountNumber));

        GlAccountCode liabilityGl = LedgerMapping.depositLiability(entity.product());
        LocalDate today = LocalDate.now(clock);

        JournalEntry je = new JournalEntry(
                UUID.randomUUID(), today,
                "FEE-REV-" + accountNumber.raw() + "-" + feeType + "-" + today,
                "Fee reversal: " + feeType + " - " + reason,
                List.of(
                        PostingLine.debit(FEE_REVENUE, amount, "fee reversal - " + feeType),
                        PostingLine.credit(liabilityGl, amount, "fee reversal credit to customer")
                )
        );
        posting.post(je);

        publishFeeEvent(accountNumber, feeType, amount, "reversed");
        log.info("Fee reversed for account {}: type={}, amount={}, approvedBy={}, reason={}",
                accountNumber, feeType, amount, approvedBy, reason);

        return new FeeAction.FeeReversed(accountNumber, feeType, amount, reason, approvedBy);
    }

    private void postFee(ConsumerAccountEntity entity, Money fee, String description,
                          GlAccountCode revenueGl, LocalDate cycleDate) {
        GlAccountCode liabilityGl = LedgerMapping.depositLiability(entity.product());
        JournalEntry je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "FEE-" + entity.accountNumber() + "-" + description.replace(" ", "-") + "-" + cycleDate,
                description,
                List.of(
                        PostingLine.debit(liabilityGl, fee, description),
                        PostingLine.credit(revenueGl, fee, description + " revenue")
                )
        );
        posting.post(je);
    }

    private RelationshipTier resolveRelationshipTier(ConsumerAccountEntity account) {
        // In production, queries all accounts for this customer and sums balances.
        // Simplified: return STANDARD tier.
        return RELATIONSHIP_TIERS.get(RELATIONSHIP_TIERS.size() - 1);
    }

    private boolean isEnrolledInEStatements(ConsumerAccountEntity account) {
        // In production, queries the notification preferences table.
        // Simplified: assume electronic statement enrollment.
        return true;
    }

    private void publishFeeEvent(AccountNumber account, String feeType,
                                  Money amount, String action) {
        events.publish(new FeeEvent(UUID.randomUUID(), Timestamp.now(clock),
                account, feeType, amount, action));
    }
}
