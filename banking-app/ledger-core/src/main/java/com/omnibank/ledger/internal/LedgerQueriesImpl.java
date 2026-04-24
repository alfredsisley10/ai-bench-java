package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class LedgerQueriesImpl implements LedgerQueries {

    private final JournalEntryRepository journals;
    private final GlAccountRepository accounts;

    public LedgerQueriesImpl(JournalEntryRepository journals, GlAccountRepository accounts) {
        this.journals = journals;
        this.accounts = accounts;
    }

    @Override
    public Money currentBalance(GlAccountCode account) {
        return balanceAsOf(account, LocalDate.now());
    }

    @Override
    public Money balanceAsOf(GlAccountCode account, LocalDate asOfInclusive) {
        GlAccountEntity meta = accounts.findById(account.value())
                .orElseThrow(() -> new IllegalArgumentException("Unknown GL account: " + account));
        List<JournalEntryEntity> affecting = journals.findJournalsForAccount(
                account.value(), LocalDate.of(1900, 1, 1), asOfInclusive);

        Money debits = Money.zero(meta.currency());
        Money credits = Money.zero(meta.currency());
        for (JournalEntryEntity je : affecting) {
            for (PostingLineEntity line : je.lines()) {
                if (!line.glAccount().equals(account.value())) continue;
                Money amt = Money.of(line.amount(), line.currency());
                if (line.direction() == PostingDirection.DEBIT) {
                    debits = debits.plus(amt);
                } else {
                    credits = credits.plus(amt);
                }
            }
        }
        return meta.type().normalBalance() == AccountType.NormalBalance.DEBIT
                ? debits.minus(credits)
                : credits.minus(debits);
    }

    @Override
    public List<PostedJournal> journalHistory(GlAccountCode account, LocalDate from, LocalDate to) {
        List<PostedJournal> out = new ArrayList<>();
        for (JournalEntryEntity je : journals.findJournalsForAccount(account.value(), from, to)) {
            List<PostingLine> lines = new ArrayList<>();
            for (PostingLineEntity l : je.lines()) {
                lines.add(new PostingLine(
                        new GlAccountCode(l.glAccount()),
                        l.direction(),
                        Money.of(l.amount(), l.currency()),
                        l.memo()));
            }
            out.add(new PostedJournal(
                    je.sequence(), je.proposalId(), je.postingDate(), je.postedAt(),
                    je.businessKey(), je.description(), lines, je.createdBy()));
        }
        return out;
    }

    @Override
    public TrialBalance trialBalance(LocalDate asOf) {
        Map<CurrencyCode, List<TrialBalance.Row>> byCcy = new EnumMap<>(CurrencyCode.class);
        for (GlAccountEntity account : accounts.findAll()) {
            Money debits = Money.zero(account.currency());
            Money credits = Money.zero(account.currency());
            List<JournalEntryEntity> affecting = journals.findJournalsForAccount(
                    account.code(), LocalDate.of(1900, 1, 1), asOf);
            for (JournalEntryEntity je : affecting) {
                for (PostingLineEntity line : je.lines()) {
                    if (!line.glAccount().equals(account.code())) continue;
                    Money amt = Money.of(line.amount(), line.currency());
                    if (line.direction() == PostingDirection.DEBIT) {
                        debits = debits.plus(amt);
                    } else {
                        credits = credits.plus(amt);
                    }
                }
            }
            byCcy.computeIfAbsent(account.currency(), c -> new ArrayList<>())
                    .add(new TrialBalance.Row(
                            new GlAccountCode(account.code()), account.type(), debits, credits));
        }
        return new TrialBalance(asOf, byCcy);
    }
}
