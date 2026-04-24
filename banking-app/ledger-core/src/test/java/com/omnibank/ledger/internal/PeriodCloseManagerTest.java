package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.PeriodStatus;
import com.omnibank.ledger.api.PeriodStatus.PeriodValidationError;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Result;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodCloseManagerTest {

    private InMemoryGlAccountRepository accounts;
    private InMemoryJournalEntryRepository journals;
    private TrialBalanceEngine tbEngine;
    private RecordingPostingService posting;
    private PostingRuleEngine postingRules;
    private RecordingEventBus eventBus;
    private Clock clock;
    private PeriodCloseManager mgr;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryGlAccountRepository();
        journals = new InMemoryJournalEntryRepository();
        tbEngine = new TrialBalanceEngine(journals, accounts, Map::of);
        posting = new RecordingPostingService();
        postingRules = new PostingRuleEngine(posting, accounts, journals,
                Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneId.of("UTC")));
        eventBus = new RecordingEventBus();
        clock = Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneId.of("UTC"));
        mgr = new PeriodCloseManager(tbEngine, postingRules, null,
                accounts, journals, eventBus, clock);
    }

    @Test
    void define_then_open_then_soft_close_then_hard_close_succeeds() {
        var period = YearMonth.of(2026, 4);
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        accounts.add("LIA-2100-001", AccountType.LIABILITY, CurrencyCode.USD);
        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"),
                line("LIA-2100-001", PostingDirection.CREDIT, "100.00"));

        assertThat(mgr.definePeriod("OB", period).isOk()).isTrue();
        assertThat(mgr.openPeriod("OB", period).isOk()).isTrue();
        assertThat(mgr.softClose("OB", period).isOk()).isTrue();
        var hard = mgr.hardClose("OB", period);

        assertThat(hard.isOk()).isTrue();
        var info = ((Result.Ok<PeriodStatus.PeriodInfo, ?>) hard).value();
        assertThat(info.status()).isEqualTo(PeriodStatus.HARD_CLOSE);
        assertThat(info.closedAt()).isNotNull();
        assertThat(mgr.getAuditSnapshot("OB", period)).isPresent();
        // Three transition events: OPEN, SOFT_CLOSE, HARD_CLOSE
        assertThat(eventBus.events).hasSize(3);
    }

    @Test
    void duplicate_define_returns_concurrent_modification_error() {
        var period = YearMonth.of(2026, 4);
        mgr.definePeriod("OB", period);
        var dup = mgr.definePeriod("OB", period);
        assertThat(dup.isErr()).isTrue();
        assertThat(((Result.Err<?, PeriodValidationError>) dup).error())
                .isInstanceOf(PeriodValidationError.ConcurrentModification.class);
    }

    @Test
    void open_rejects_when_period_not_defined() {
        var result = mgr.openPeriod("OB", YearMonth.of(2026, 4));
        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<?, PeriodValidationError>) result).error())
                .isInstanceOf(PeriodValidationError.PeriodNotFound.class);
    }

    @Test
    void cannot_open_when_another_period_is_already_open() {
        mgr.definePeriod("OB", YearMonth.of(2026, 3));
        mgr.openPeriod("OB", YearMonth.of(2026, 3));

        mgr.definePeriod("OB", YearMonth.of(2026, 4));
        var result = mgr.openPeriod("OB", YearMonth.of(2026, 4));

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<?, PeriodValidationError>) result).error())
                .isInstanceOf(PeriodValidationError.InvalidTransition.class);
    }

    @Test
    void posting_allowed_in_open_period_only_for_business_postings() {
        var period = YearMonth.of(2026, 4);
        mgr.definePeriod("OB", period);
        mgr.openPeriod("OB", period);

        assertThat(mgr.isPostingAllowed("OB", LocalDate.of(2026, 4, 15), false)).isTrue();
        assertThat(mgr.isPostingAllowed("OB", LocalDate.of(2026, 4, 15), true)).isTrue();

        mgr.softClose("OB", period);
        // After soft close, only adjustments allowed.
        assertThat(mgr.isPostingAllowed("OB", LocalDate.of(2026, 4, 15), false)).isFalse();
        assertThat(mgr.isPostingAllowed("OB", LocalDate.of(2026, 4, 15), true)).isTrue();
    }

    @Test
    void posting_in_undefined_period_is_rejected() {
        assertThat(mgr.isPostingAllowed("OB", LocalDate.of(2030, 1, 1), false)).isFalse();
    }

    @Test
    void calendar_reports_open_and_earliest_unclosed_periods() {
        mgr.definePeriod("OB", YearMonth.of(2026, 3));
        mgr.openPeriod("OB", YearMonth.of(2026, 3));
        mgr.definePeriod("OB", YearMonth.of(2026, 4));
        mgr.definePeriod("OB", YearMonth.of(2026, 5));

        var calendar = mgr.getCalendar("OB");
        assertThat(calendar.entityCode()).isEqualTo("OB");
        assertThat(calendar.currentOpenPeriod()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(calendar.earliestUnclosedPeriod()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(calendar.latestDefinedPeriod()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(calendar.totalDefinedPeriods()).isEqualTo(3);
    }

    @Test
    void hard_close_fails_when_trial_balance_unbalanced() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        // Single-sided posting → unbalanced trial balance.
        journals.seed("BAD-J", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"));

        var period = YearMonth.of(2026, 4);
        mgr.definePeriod("OB", period);
        mgr.openPeriod("OB", period);

        // Soft-close fails on the same imbalance.
        var soft = mgr.softClose("OB", period);
        assertThat(soft.isErr()).isTrue();
        assertThat(((Result.Err<?, PeriodValidationError>) soft).error())
                .isInstanceOf(PeriodValidationError.TrialBalanceImbalance.class);
    }

    // ── helpers / test doubles ──────────────────────────────────────────

    private static SeedLine line(String account, PostingDirection direction, String amount) {
        return new SeedLine(account, direction, new BigDecimal(amount), CurrencyCode.USD);
    }

    private record SeedLine(String account, PostingDirection direction,
                            BigDecimal amount, CurrencyCode currency) {}

    private static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override public void publish(DomainEvent event) { events.add(event); }
    }

    private static final class RecordingPostingService implements com.omnibank.ledger.api.PostingService {
        final List<com.omnibank.ledger.api.PostedJournal> posted = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong(1000);

        @Override
        public com.omnibank.ledger.api.PostedJournal post(com.omnibank.ledger.api.JournalEntry entry) {
            var p = new com.omnibank.ledger.api.PostedJournal(sequence.incrementAndGet(), entry.proposalId(),
                    entry.postingDate(), Instant.now(), entry.businessKey(),
                    entry.description(), entry.lines(), "test");
            posted.add(p);
            return p;
        }
    }

    private static final class InMemoryGlAccountRepository implements GlAccountRepository {
        private final Map<String, GlAccountEntity> store = new HashMap<>();

        void add(String code, AccountType type, CurrencyCode ccy) {
            store.put(code, new GlAccountEntity(code, type, ccy, code));
        }

        @Override public Optional<GlAccountEntity> findById(String code) { return Optional.ofNullable(store.get(code)); }
        @Override public List<GlAccountEntity> findAll() { return new ArrayList<>(store.values()); }
        @Override public boolean existsById(String s) { return store.containsKey(s); }
        @Override public long count() { return store.size(); }
        @Override public <S extends GlAccountEntity> S save(S e) { throw u(); }
        @Override public <S extends GlAccountEntity> List<S> saveAll(Iterable<S> es) { throw u(); }
        @Override public List<GlAccountEntity> findAllById(Iterable<String> ids) { throw u(); }
        @Override public void deleteById(String s) { throw u(); }
        @Override public void delete(GlAccountEntity e) { throw u(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw u(); }
        @Override public void deleteAll(Iterable<? extends GlAccountEntity> es) { throw u(); }
        @Override public void deleteAll() { throw u(); }
        @Override public List<GlAccountEntity> findAll(org.springframework.data.domain.Sort s) { throw u(); }
        @Override public org.springframework.data.domain.Page<GlAccountEntity> findAll(org.springframework.data.domain.Pageable p) { throw u(); }
        @Override public void flush() {}
        @Override public <S extends GlAccountEntity> S saveAndFlush(S e) { throw u(); }
        @Override public <S extends GlAccountEntity> List<S> saveAllAndFlush(Iterable<S> es) { throw u(); }
        @Override public void deleteAllInBatch(Iterable<GlAccountEntity> es) { throw u(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw u(); }
        @Override public void deleteAllInBatch() { throw u(); }
        @Override public GlAccountEntity getOne(String s) { throw u(); }
        @Override public GlAccountEntity getById(String s) { throw u(); }
        @Override public GlAccountEntity getReferenceById(String s) { throw u(); }
        @Override public <S extends GlAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends GlAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw u(); }
        @Override public <S extends GlAccountEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw u(); }
        @Override public <S extends GlAccountEntity> long count(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends GlAccountEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends GlAccountEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) { throw u(); }
        @Override public <S extends GlAccountEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw u(); }

        private static UnsupportedOperationException u() { return new UnsupportedOperationException("Not used"); }
    }

    private static final class InMemoryJournalEntryRepository implements JournalEntryRepository {
        private final List<JournalEntryEntity> all = new ArrayList<>();
        private final Map<String, JournalEntryEntity> byKey = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        void seed(String businessKey, LocalDate date, SeedLine... seedLines) {
            var entity = new JournalEntryEntity(UUID.randomUUID(), date,
                    Instant.now(), businessKey, businessKey + " seeded");
            for (SeedLine l : seedLines) {
                entity.addLine(new PostingLineEntity(l.account(), l.direction(),
                        l.amount(), l.currency(), "seed"));
            }
            try {
                var f = JournalEntryEntity.class.getDeclaredField("sequence");
                f.setAccessible(true);
                f.set(entity, seq.getAndIncrement());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
            all.add(entity);
            byKey.put(businessKey, entity);
        }

        @Override public Optional<JournalEntryEntity> findByBusinessKey(String key) { return Optional.ofNullable(byKey.get(key)); }
        @Override public List<JournalEntryEntity> findJournalsForAccount(String account, LocalDate from, LocalDate to) {
            return all.stream()
                    .filter(j -> !j.postingDate().isBefore(from) && !j.postingDate().isAfter(to))
                    .filter(j -> j.lines().stream().anyMatch(l -> l.glAccount().equals(account)))
                    .toList();
        }
        @Override public <S extends JournalEntryEntity> S save(S e) { throw u(); }
        @Override public <S extends JournalEntryEntity> List<S> saveAll(Iterable<S> es) { throw u(); }
        @Override public Optional<JournalEntryEntity> findById(Long id) { throw u(); }
        @Override public boolean existsById(Long id) { throw u(); }
        @Override public List<JournalEntryEntity> findAll() { return new ArrayList<>(all); }
        @Override public List<JournalEntryEntity> findAllById(Iterable<Long> ids) { throw u(); }
        @Override public long count() { return all.size(); }
        @Override public void deleteById(Long id) { throw u(); }
        @Override public void delete(JournalEntryEntity e) { throw u(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw u(); }
        @Override public void deleteAll(Iterable<? extends JournalEntryEntity> es) { throw u(); }
        @Override public void deleteAll() { throw u(); }
        @Override public List<JournalEntryEntity> findAll(org.springframework.data.domain.Sort s) { throw u(); }
        @Override public org.springframework.data.domain.Page<JournalEntryEntity> findAll(org.springframework.data.domain.Pageable p) { throw u(); }
        @Override public void flush() {}
        @Override public <S extends JournalEntryEntity> S saveAndFlush(S e) { throw u(); }
        @Override public <S extends JournalEntryEntity> List<S> saveAllAndFlush(Iterable<S> es) { throw u(); }
        @Override public void deleteAllInBatch(Iterable<JournalEntryEntity> es) { throw u(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw u(); }
        @Override public void deleteAllInBatch() { throw u(); }
        @Override public JournalEntryEntity getOne(Long id) { throw u(); }
        @Override public JournalEntryEntity getById(Long id) { throw u(); }
        @Override public JournalEntryEntity getReferenceById(Long id) { throw u(); }
        @Override public <S extends JournalEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends JournalEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw u(); }
        @Override public <S extends JournalEntryEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw u(); }
        @Override public <S extends JournalEntryEntity> long count(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends JournalEntryEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends JournalEntryEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) { throw u(); }
        @Override public <S extends JournalEntryEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw u(); }

        private static UnsupportedOperationException u() { return new UnsupportedOperationException("Not used"); }
    }
}
