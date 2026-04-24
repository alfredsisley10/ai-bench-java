package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.cards.api.CardToken;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared fakes used across the cards test suite.
 */
final class CardsTestSupport {

    private CardsTestSupport() {}

    static CardEntity activeCreditCard(UUID cardId) {
        var token = new CardToken(UUID.randomUUID(), CardNetwork.VISA, "4242");
        return new CardEntity(
                cardId,
                new CustomerId(UUID.randomUUID()),
                AccountNumber.of("OB-X-ABC12345"),
                CardProduct.CREDIT_REWARDS,
                CardNetwork.VISA,
                token,
                CardStatus.ACTIVE,
                false,
                LocalDate.of(2030, 1, 1),
                Money.of("10000.00", CurrencyCode.USD),
                Money.of("10000.00", CurrencyCode.USD),
                CurrencyCode.USD,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                "Initially issued",
                0);
    }

    static CardEntity activeDebitCard(UUID cardId) {
        var token = new CardToken(UUID.randomUUID(), CardNetwork.VISA, "1111");
        return new CardEntity(
                cardId,
                new CustomerId(UUID.randomUUID()),
                AccountNumber.of("OB-X-XYZ99999"),
                CardProduct.DEBIT_STANDARD,
                CardNetwork.VISA,
                token,
                CardStatus.ACTIVE,
                false,
                LocalDate.of(2030, 1, 1),
                null,
                null,
                CurrencyCode.USD,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                "Initial",
                0);
    }

    static AuthorizationRequest sampleRequest(UUID cardId, String amount, String mcc) {
        return new AuthorizationRequest(
                cardId,
                Money.of(amount, CurrencyCode.USD),
                mcc,
                "Acme Corp",
                "US",
                true, true, true, false, false, false,
                "device-123",
                "acquirer-1",
                "auth-code-1",
                Instant.parse("2026-04-16T16:00:00Z"));
    }

    static AuthorizationRequest eCommerceRequest(UUID cardId, String amount, String mcc) {
        return new AuthorizationRequest(
                cardId,
                Money.of(amount, CurrencyCode.USD),
                mcc,
                "Online Inc",
                "US",
                false, false, false, false, true, false,
                "device-ecom",
                "acquirer-online",
                null,
                Instant.parse("2026-04-16T16:00:00Z"));
    }

    /** Captures every event published — simple recording bus. */
    static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();
        boolean throwOnNext = false;

        @Override
        public void publish(DomainEvent event) {
            if (throwOnNext) {
                throwOnNext = false;
                throw new RuntimeException("event bus boom");
            }
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        <T extends DomainEvent> List<T> eventsOfType(Class<T> type) {
            var out = new ArrayList<T>();
            for (var e : events) if (type.isInstance(e)) out.add((T) e);
            return out;
        }
    }

    /** Captures every journal entry without writing to a real ledger. */
    static final class RecordingPostingService implements PostingService {
        final List<JournalEntry> posted = new ArrayList<>();
        final AtomicLong seq = new AtomicLong();
        boolean throwOnNext = false;

        @Override
        public PostedJournal post(JournalEntry entry) {
            if (throwOnNext) {
                throwOnNext = false;
                throw new com.omnibank.ledger.api.PostingException(
                        com.omnibank.ledger.api.PostingException.Reason.DUPLICATE_BUSINESS_KEY,
                        "forced failure");
            }
            if (!entry.isBalanced()) {
                throw new com.omnibank.ledger.api.PostingException(
                        com.omnibank.ledger.api.PostingException.Reason.UNBALANCED,
                        "Unbalanced entry: debits=" + entry.debitTotal()
                                + " credits=" + entry.creditTotal());
            }
            posted.add(entry);
            return new PostedJournal(seq.incrementAndGet(),
                    entry.proposalId(), entry.postingDate(),
                    Instant.now(), entry.businessKey(),
                    entry.description(), entry.lines(), "test");
        }
    }
}
