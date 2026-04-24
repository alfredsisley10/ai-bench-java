package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingException;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.EventBus;
import com.omnibank.shared.security.PrincipalContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Service
public class PostingServiceImpl implements PostingService {

    private final JournalEntryRepository journals;
    private final GlAccountRepository accounts;
    private final EventBus events;
    private final Clock clock;

    public PostingServiceImpl(JournalEntryRepository journals,
                              GlAccountRepository accounts,
                              EventBus events,
                              Clock clock) {
        this.journals = journals;
        this.accounts = accounts;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PostedJournal post(JournalEntry entry) {
        validate(entry);

        JournalEntryEntity je = new JournalEntryEntity(
                entry.proposalId(),
                entry.postingDate(),
                Timestamp.now(clock),
                entry.businessKey(),
                entry.description()
        );
        for (PostingLine line : entry.lines()) {
            je.addLine(new PostingLineEntity(
                    line.account().value(),
                    line.direction(),
                    line.amount().amount(),
                    line.amount().currency(),
                    line.memo()
            ));
        }
        JournalEntryEntity saved = journals.save(je);

        PostedJournal posted = new PostedJournal(
                saved.sequence(),
                saved.proposalId(),
                saved.postingDate(),
                saved.postedAt(),
                saved.businessKey(),
                saved.description(),
                entry.lines(),
                PrincipalContext.current().getName()
        );
        events.publish(new JournalPostedEvent(posted));
        return posted;
    }

    private void validate(JournalEntry entry) {
        // 1. Balance
        if (!entry.isBalanced()) {
            throw new PostingException(PostingException.Reason.UNBALANCED,
                    "debits=" + entry.debitTotal() + " credits=" + entry.creditTotal());
        }
        // 2. Single-currency rule (no cross-currency journals; FX books to an FX gain/loss account separately)
        Set<CurrencyCode> currencies = new HashSet<>();
        for (PostingLine l : entry.lines()) {
            currencies.add(l.amount().currency());
        }
        if (currencies.size() > 1) {
            throw new PostingException(PostingException.Reason.MIXED_CURRENCIES, currencies.toString());
        }
        // 3. Posting date sanity — no future dates (adjusting entries go through a separate pathway)
        if (entry.postingDate().isAfter(LocalDate.now(clock))) {
            throw new PostingException(PostingException.Reason.FUTURE_POST_DATE, entry.postingDate().toString());
        }
        // 4. All referenced accounts exist and are open
        for (PostingLine l : entry.lines()) {
            GlAccountEntity account = accounts.findById(l.account().value())
                    .orElseThrow(() -> new PostingException(
                            PostingException.Reason.UNKNOWN_ACCOUNT, l.account().toString()));
            if (account.isClosed()) {
                throw new PostingException(PostingException.Reason.ACCOUNT_CLOSED, l.account().toString());
            }
            if (account.currency() != l.amount().currency()) {
                throw new PostingException(PostingException.Reason.MIXED_CURRENCIES,
                        "account=" + account.currency() + " line=" + l.amount().currency());
            }
        }
        // 5. Duplicate business-key guard (idempotency)
        journals.findByBusinessKey(entry.businessKey()).ifPresent(prior -> {
            throw new PostingException(PostingException.Reason.DUPLICATE_BUSINESS_KEY, entry.businessKey());
        });
    }
}
