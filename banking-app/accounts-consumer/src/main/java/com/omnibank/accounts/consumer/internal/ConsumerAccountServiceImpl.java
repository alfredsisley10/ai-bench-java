package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountOpening;
import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.BalanceChangedEvent;
import com.omnibank.accounts.consumer.api.BalanceView;
import com.omnibank.accounts.consumer.api.ConsumerAccountService;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.EventBus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ConsumerAccountServiceImpl implements ConsumerAccountService {

    private final ConsumerAccountRepository accounts;
    private final HoldRepository holds;
    private final PostingService posting;
    private final LedgerQueries ledger;
    private final EventBus events;
    private final ConsumerAccountNumbers numbering = new ConsumerAccountNumbers();
    private final Clock clock;

    public ConsumerAccountServiceImpl(ConsumerAccountRepository accounts,
                                      HoldRepository holds,
                                      PostingService posting,
                                      LedgerQueries ledger,
                                      EventBus events,
                                      Clock clock) {
        this.accounts = accounts;
        this.holds = holds;
        this.posting = posting;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AccountNumber open(AccountOpening.Request request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate matures = request.product().isMaturing()
                ? request.product().term.applyTo(today)
                : null;

        AccountNumber number = numbering.generateValid(request.product());
        ConsumerAccountEntity entity = new ConsumerAccountEntity(
                number.raw(), request.customer().value(), request.product(),
                request.currency(), today, matures);
        entity.activate();
        accounts.save(entity);

        request.initialDeposit().ifPresent(m -> {
            if (m.isPositive()) {
                postCustomerDeposit(number, m);
            }
        });
        return number;
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceView balance(AccountNumber account) {
        ConsumerAccountEntity entity = require(account);
        Money ledgerBalance = ledger.currentBalance(LedgerMapping.depositLiability(entity.product()));
        Money held = activeHoldsTotal(account, entity.currency());
        return new BalanceView(account, ledgerBalance, ledgerBalance.minus(held), held);
    }

    @Override
    @Transactional
    public void freeze(AccountNumber account, String reason) {
        require(account).freeze(reason);
    }

    @Override
    @Transactional
    public void unfreeze(AccountNumber account) {
        require(account).unfreeze();
    }

    @Override
    @Transactional
    public void close(AccountNumber account, String reason) {
        require(account).close(Timestamp.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountStatus status(AccountNumber account) {
        return require(account).status();
    }

    private ConsumerAccountEntity require(AccountNumber account) {
        return accounts.findById(account.raw())
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + account));
    }

    private Money activeHoldsTotal(AccountNumber account, com.omnibank.shared.domain.CurrencyCode ccy) {
        Instant now = Timestamp.now(clock);
        List<HoldEntity> active = holds.findByAccountNumberAndReleasedAtIsNull(account.raw());
        Money total = Money.zero(ccy);
        for (HoldEntity h : active) {
            if (h.isActive(now)) {
                total = total.plus(Money.of(h.amount(), h.currency()));
            }
        }
        return total;
    }

    private void postCustomerDeposit(AccountNumber account, Money amount) {
        ConsumerAccountEntity entity = require(account);
        Money priorBalance = ledger.currentBalance(LedgerMapping.depositLiability(entity.product()));
        JournalEntry je = new JournalEntry(
                UUID.randomUUID(),
                LocalDate.now(clock),
                "ACCT-OPEN-DEP-" + account.raw(),
                "Initial deposit on account open",
                List.of(
                        PostingLine.debit(LedgerMapping.CASH_AT_FED, amount, "customer cash in"),
                        PostingLine.credit(LedgerMapping.depositLiability(entity.product()),
                                amount, "dda liability")
                )
        );
        posting.post(je);
        events.publish(new BalanceChangedEvent(
                UUID.randomUUID(), Timestamp.now(clock), account,
                priorBalance, priorBalance.plus(amount), "initial deposit"));
    }
}
