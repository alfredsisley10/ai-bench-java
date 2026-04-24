package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.BalanceView;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Overdraft protection engine for consumer checking accounts. When a debit
 * transaction would cause the available balance to go negative, this service
 * attempts recovery in priority order:
 *
 * <ol>
 *   <li>Linked savings account transfer (free or nominal fee)</li>
 *   <li>Overdraft line of credit draw (interest-bearing)</li>
 *   <li>Standard overdraft coverage with NSF fee (if customer opted in)</li>
 *   <li>Transaction decline (if no coverage elected)</li>
 * </ol>
 *
 * <p>Regulation E compliance: overdraft coverage for ATM and one-time debit
 * transactions requires affirmative opt-in. Recurring debits and checks are
 * covered by default under standard overdraft practices.
 */
public class OverdraftProtectionService {

    private static final Logger log = LoggerFactory.getLogger(OverdraftProtectionService.class);

    /** Standard NSF/overdraft fee for covered transactions. */
    private static final Money OVERDRAFT_FEE = Money.of("35.00", CurrencyCode.USD);

    /** Maximum number of overdraft fees per day (Reg E best-practice cap). */
    private static final int MAX_DAILY_OD_FEES = 4;

    /** Overdraft line of credit maximum draw amount. */
    private static final Money OD_LINE_MAX = Money.of("1000.00", CurrencyCode.USD);

    /** Linked account transfer fee. */
    private static final Money LINKED_TRANSFER_FEE = Money.of("10.00", CurrencyCode.USD);

    /** GL codes for overdraft-related postings. */
    private static final GlAccountCode OD_FEE_REVENUE = new GlAccountCode("REV-4200-001");
    private static final GlAccountCode OD_LINE_LIABILITY = new GlAccountCode("LIA-2300-001");
    private static final GlAccountCode TRANSFER_FEE_REVENUE = new GlAccountCode("REV-4200-002");

    /** Transaction channels requiring explicit Reg E opt-in for overdraft coverage. */
    enum TransactionChannel {
        ATM, POINT_OF_SALE, ONLINE_BANKING, ACH, CHECK, WIRE;

        boolean requiresOptIn() {
            return this == ATM || this == POINT_OF_SALE;
        }
    }

    sealed interface OverdraftDecision permits
            OverdraftDecision.Covered,
            OverdraftDecision.LinkedTransfer,
            OverdraftDecision.LineOfCredit,
            OverdraftDecision.Declined {

        record Covered(Money shortfall, Money feeAssessed, String method) implements OverdraftDecision {}
        record LinkedTransfer(AccountNumber sourceAccount, Money transferAmount,
                              Money feeAssessed) implements OverdraftDecision {}
        record LineOfCredit(Money drawAmount, Money availableLine) implements OverdraftDecision {}
        record Declined(Money shortfall, String reason) implements OverdraftDecision {}
    }

    record OverdraftOptIn(AccountNumber account, boolean standardOverdraft,
                          boolean atmAndDebit, Optional<AccountNumber> linkedSavings,
                          Optional<BigDecimal> lineOfCreditLimit, Instant enrolledAt) {}

    record OverdraftEvent(UUID eventId, Instant occurredAt, AccountNumber account,
                          Money transactionAmount, Money shortfall,
                          String resolution) implements DomainEvent {
        @Override
        public String eventType() {
            return "accounts.consumer.overdraft_evaluated";
        }
    }

    private final ConsumerAccountRepository accounts;
    private final LedgerQueries ledger;
    private final PostingService posting;
    private final EventBus events;
    private final Clock clock;

    public OverdraftProtectionService(ConsumerAccountRepository accounts,
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
     * Evaluate whether a transaction that would overdraw the account can proceed.
     * Returns the decision including any fees assessed or transfers initiated.
     */
    @Transactional
    public OverdraftDecision evaluate(AccountNumber accountNumber, Money transactionAmount,
                                      TransactionChannel channel) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(transactionAmount, "transactionAmount");
        Objects.requireNonNull(channel, "channel");

        ConsumerAccountEntity entity = requireAccount(accountNumber);
        validateAccountEligible(entity);

        Money availableBalance = currentAvailable(entity);
        Money shortfall = transactionAmount.minus(availableBalance);

        if (!shortfall.isPositive()) {
            // No overdraft situation — sufficient funds
            return new OverdraftDecision.Covered(Money.zero(entity.currency()),
                    Money.zero(entity.currency()), "sufficient_funds");
        }

        OverdraftOptIn optIn = resolveOptIn(accountNumber);
        Instant now = Timestamp.now(clock);

        // Priority 1: Linked savings account transfer
        if (optIn.linkedSavings().isPresent()) {
            OverdraftDecision linkedResult = attemptLinkedTransfer(
                    optIn.linkedSavings().get(), accountNumber, shortfall, entity.currency());
            if (linkedResult instanceof OverdraftDecision.LinkedTransfer) {
                publishEvent(accountNumber, transactionAmount, shortfall, "linked_transfer", now);
                return linkedResult;
            }
        }

        // Priority 2: Overdraft line of credit
        if (optIn.lineOfCreditLimit().isPresent()) {
            Money lineLimit = Money.of(optIn.lineOfCreditLimit().get(), entity.currency());
            OverdraftDecision lineResult = attemptLineOfCredit(
                    accountNumber, shortfall, lineLimit, entity.currency());
            if (lineResult instanceof OverdraftDecision.LineOfCredit) {
                publishEvent(accountNumber, transactionAmount, shortfall, "line_of_credit", now);
                return lineResult;
            }
        }

        // Priority 3: Standard overdraft (fee-based)
        if (isOverdraftCoverageAvailable(optIn, channel)) {
            int feesToday = countOverdraftFeesToday(accountNumber);
            if (feesToday >= MAX_DAILY_OD_FEES) {
                log.info("Daily overdraft fee cap reached for account {}", accountNumber);
                return new OverdraftDecision.Declined(shortfall,
                        "Daily overdraft fee limit reached (" + MAX_DAILY_OD_FEES + ")");
            }

            postOverdraftFee(accountNumber, entity);
            publishEvent(accountNumber, transactionAmount, shortfall, "standard_overdraft", now);
            return new OverdraftDecision.Covered(shortfall, OVERDRAFT_FEE, "standard_overdraft");
        }

        // Priority 4: Decline
        publishEvent(accountNumber, transactionAmount, shortfall, "declined", now);
        return new OverdraftDecision.Declined(shortfall,
                channel.requiresOptIn()
                        ? "Customer has not opted in to overdraft coverage for " + channel
                        : "No overdraft protection available");
    }

    /**
     * Enroll or update overdraft protection preferences for an account.
     * Reg E requires clear disclosure and affirmative consent for ATM/POS coverage.
     */
    @Transactional
    public OverdraftOptIn enrollOverdraftProtection(AccountNumber accountNumber,
                                                     boolean standardOverdraft,
                                                     boolean atmAndDebit,
                                                     Optional<AccountNumber> linkedSavings,
                                                     String consentChannel) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(consentChannel, "consentChannel");
        requireAccount(accountNumber);

        log.info("Overdraft enrollment for {}: standard={}, atm/debit={}, linked={}, channel={}",
                accountNumber, standardOverdraft, atmAndDebit,
                linkedSavings.map(AccountNumber::raw).orElse("none"), consentChannel);

        return new OverdraftOptIn(accountNumber, standardOverdraft, atmAndDebit,
                linkedSavings, Optional.empty(), Timestamp.now(clock));
    }

    private OverdraftDecision attemptLinkedTransfer(AccountNumber savingsAccount,
                                                     AccountNumber checkingAccount,
                                                     Money shortfall,
                                                     CurrencyCode currency) {
        Optional<ConsumerAccountEntity> savingsOpt = accounts.findById(savingsAccount.raw());
        if (savingsOpt.isEmpty() || savingsOpt.get().status() != AccountStatus.OPEN) {
            return new OverdraftDecision.Declined(shortfall, "Linked savings account unavailable");
        }

        Money savingsBalance = currentAvailable(savingsOpt.get());
        Money totalNeeded = shortfall.plus(LINKED_TRANSFER_FEE);

        if (savingsBalance.compareTo(totalNeeded) < 0) {
            return new OverdraftDecision.Declined(shortfall,
                    "Insufficient balance in linked savings for transfer");
        }

        postLinkedTransfer(savingsAccount, checkingAccount, shortfall, currency);
        return new OverdraftDecision.LinkedTransfer(savingsAccount, shortfall, LINKED_TRANSFER_FEE);
    }

    private OverdraftDecision attemptLineOfCredit(AccountNumber account, Money shortfall,
                                                   Money lineLimit, CurrencyCode currency) {
        Money effectiveLimit = lineLimit.compareTo(OD_LINE_MAX) <= 0 ? lineLimit : OD_LINE_MAX;
        Money currentDraw = resolveCurrentLineDraw(account, currency);
        Money availableLine = effectiveLimit.minus(currentDraw);

        if (availableLine.compareTo(shortfall) < 0) {
            return new OverdraftDecision.Declined(shortfall,
                    "Overdraft line of credit insufficient (available: " + availableLine + ")");
        }

        postLineOfCreditDraw(account, shortfall, currency);
        return new OverdraftDecision.LineOfCredit(shortfall, availableLine.minus(shortfall));
    }

    private boolean isOverdraftCoverageAvailable(OverdraftOptIn optIn, TransactionChannel channel) {
        if (channel.requiresOptIn()) {
            return optIn.atmAndDebit();
        }
        return optIn.standardOverdraft();
    }

    private void postOverdraftFee(AccountNumber account, ConsumerAccountEntity entity) {
        GlAccountCode liabilityGl = LedgerMapping.depositLiability(entity.product());
        JournalEntry je = new JournalEntry(
                UUID.randomUUID(), LocalDate.now(clock),
                "OD-FEE-" + account.raw() + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Overdraft fee assessment",
                List.of(
                        PostingLine.debit(liabilityGl, OVERDRAFT_FEE, "overdraft fee charge"),
                        PostingLine.credit(OD_FEE_REVENUE, OVERDRAFT_FEE, "overdraft fee revenue")
                )
        );
        posting.post(je);
    }

    private void postLinkedTransfer(AccountNumber from, AccountNumber to,
                                     Money amount, CurrencyCode currency) {
        GlAccountCode savingsGl = LedgerMapping.SAVINGS_LIABILITY;
        GlAccountCode checkingGl = LedgerMapping.CHECKING_LIABILITY;
        List<PostingLine> lines = new ArrayList<>();
        lines.add(PostingLine.debit(savingsGl, amount, "OD protection transfer out"));
        lines.add(PostingLine.credit(checkingGl, amount, "OD protection transfer in"));
        if (LINKED_TRANSFER_FEE.isPositive()) {
            lines.add(PostingLine.debit(savingsGl, LINKED_TRANSFER_FEE, "OD transfer fee"));
            lines.add(PostingLine.credit(TRANSFER_FEE_REVENUE, LINKED_TRANSFER_FEE, "OD transfer fee revenue"));
        }

        JournalEntry je = new JournalEntry(UUID.randomUUID(), LocalDate.now(clock),
                "OD-XFER-" + from.raw() + "-" + to.raw() + "-" + LocalDate.now(clock),
                "Overdraft protection linked transfer", lines);
        posting.post(je);
    }

    private void postLineOfCreditDraw(AccountNumber account, Money amount, CurrencyCode currency) {
        GlAccountCode checkingGl = LedgerMapping.CHECKING_LIABILITY;
        JournalEntry je = new JournalEntry(UUID.randomUUID(), LocalDate.now(clock),
                "OD-LOC-" + account.raw() + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Overdraft line of credit draw",
                List.of(
                        PostingLine.debit(OD_LINE_LIABILITY, amount, "OD line draw"),
                        PostingLine.credit(checkingGl, amount, "OD line credit to checking")
                ));
        posting.post(je);
    }

    private Money currentAvailable(ConsumerAccountEntity entity) {
        GlAccountCode liabilityGl = LedgerMapping.depositLiability(entity.product());
        return ledger.currentBalance(liabilityGl);
    }

    private Money resolveCurrentLineDraw(AccountNumber account, CurrencyCode currency) {
        // In production, queries the OD line sub-ledger for outstanding draw balance.
        return Money.zero(currency);
    }

    private int countOverdraftFeesToday(AccountNumber account) {
        // In production, queries today's posted OD fee journal entries for this account.
        return 0;
    }

    private OverdraftOptIn resolveOptIn(AccountNumber account) {
        // In production, queries the overdraft_opt_in table.
        // Default: standard overdraft enabled, ATM/debit not opted in, no linked account.
        return new OverdraftOptIn(account, true, false, Optional.empty(),
                Optional.empty(), Timestamp.now(clock));
    }

    private void validateAccountEligible(ConsumerAccountEntity entity) {
        if (entity.status() != AccountStatus.OPEN) {
            throw new IllegalStateException(
                    "Overdraft evaluation requires OPEN account; current status: " + entity.status());
        }
        if (entity.product().kind != ConsumerProduct.Kind.CHECKING) {
            throw new IllegalStateException(
                    "Overdraft protection applies only to checking accounts");
        }
    }

    private void publishEvent(AccountNumber account, Money txnAmount, Money shortfall,
                               String resolution, Instant when) {
        events.publish(new OverdraftEvent(UUID.randomUUID(), when, account,
                txnAmount, shortfall, resolution));
    }

    private ConsumerAccountEntity requireAccount(AccountNumber accountNumber) {
        return accounts.findById(accountNumber.raw())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown account: " + accountNumber));
    }
}
