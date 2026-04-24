package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrialBalanceEngineTest {

    private InMemoryGlAccountRepository accounts;
    private InMemoryJournalEntryRepository journals;
    private TrialBalanceEngine engine;
    private final Supplier<Map<CurrencyCode, ExchangeRate>> rates = () -> Map.of(
            CurrencyCode.EUR,
            new ExchangeRate(CurrencyCode.EUR, CurrencyCode.USD,
                    new BigDecimal("1.10"), Instant.parse("2026-04-19T00:00:00Z"))
    );

    @BeforeEach
    void setUp() {
        accounts = new InMemoryGlAccountRepository();
        journals = new InMemoryJournalEntryRepository();
        engine = new TrialBalanceEngine(journals, accounts, rates);
    }

    @Test
    void trial_balance_aggregates_debits_and_credits_per_account() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        accounts.add("LIA-2100-001", AccountType.LIABILITY, CurrencyCode.USD);

        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "1000.00"),
                line("LIA-2100-001", PostingDirection.CREDIT, "1000.00"));
        journals.seed("J2", LocalDate.of(2026, 4, 10),
                line("ASS-1100-001", PostingDirection.DEBIT, "500.00"),
                line("LIA-2100-001", PostingDirection.CREDIT, "500.00"));

        TrialBalance tb = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));

        var rows = tb.byCurrency().get(CurrencyCode.USD);
        assertThat(rows).hasSize(2);
        var asset = rows.stream().filter(r -> r.account().value().equals("ASS-1100-001")).findFirst().orElseThrow();
        var liab  = rows.stream().filter(r -> r.account().value().equals("LIA-2100-001")).findFirst().orElseThrow();
        assertThat(asset.debit()).isEqualTo(Money.of("1500.00", CurrencyCode.USD));
        assertThat(asset.credit()).isEqualTo(Money.zero(CurrencyCode.USD));
        assertThat(liab.credit()).isEqualTo(Money.of("1500.00", CurrencyCode.USD));
        assertThat(tb.invariantHolds(CurrencyCode.USD)).isTrue();
    }

    @Test
    void closed_accounts_are_omitted() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        accounts.addClosed("ASS-1100-002", AccountType.ASSET, CurrencyCode.USD);
        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"),
                line("ASS-1100-002", PostingDirection.DEBIT, "999.00"));

        TrialBalance tb = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));

        var rows = tb.byCurrency().get(CurrencyCode.USD);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).account().value()).isEqualTo("ASS-1100-001");
    }

    @Test
    void postings_after_asof_are_excluded() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"));
        journals.seed("J2", LocalDate.of(2026, 5, 1),
                line("ASS-1100-001", PostingDirection.DEBIT, "999.99"));

        TrialBalance tb = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));

        var row = tb.byCurrency().get(CurrencyCode.USD).get(0);
        assertThat(row.debit()).isEqualTo(Money.of("100.00", CurrencyCode.USD));
    }

    @Test
    void cache_returns_stale_result_until_invalidated() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"));

        // First call populates the cache.
        var first = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));
        assertThat(first.byCurrency().get(CurrencyCode.USD).get(0).debit())
                .isEqualTo(Money.of("100.00", CurrencyCode.USD));

        // Add another posting — without invalidation, cache still serves the old balance.
        journals.seed("J2", LocalDate.of(2026, 4, 10),
                line("ASS-1100-001", PostingDirection.DEBIT, "200.00"));
        var cached = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));
        assertThat(cached.byCurrency().get(CurrencyCode.USD).get(0).debit())
                .isEqualTo(Money.of("100.00", CurrencyCode.USD));

        // After invalidation the new posting is reflected.
        engine.invalidateAccounts(List.of("ASS-1100-001"));
        var fresh = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));
        assertThat(fresh.byCurrency().get(CurrencyCode.USD).get(0).debit())
                .isEqualTo(Money.of("300.00", CurrencyCode.USD));
    }

    @Test
    void consolidated_balance_converts_other_currency_at_published_rate() {
        accounts.add("ASS-1900-001", AccountType.ASSET, CurrencyCode.EUR);
        journals.seed("EUR-J1", LocalDate.of(2026, 4, 5),
                line("ASS-1900-001", PostingDirection.DEBIT, "100.00", CurrencyCode.EUR));

        var consolidated = engine.computeConsolidatedTrialBalance(
                List.of("OB"), LocalDate.of(2026, 4, 30), CurrencyCode.USD);

        var rows = consolidated.byCurrency().get(CurrencyCode.USD);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).debit()).isEqualTo(Money.of("110.00", CurrencyCode.USD));
    }

    @Test
    void consolidated_balance_throws_when_rate_missing() {
        accounts.add("ASS-1800-001", AccountType.ASSET, CurrencyCode.GBP);
        journals.seed("GBP-J1", LocalDate.of(2026, 4, 5),
                line("ASS-1800-001", PostingDirection.DEBIT, "100.00", CurrencyCode.GBP));

        assertThatThrownBy(() -> engine.computeConsolidatedTrialBalance(
                List.of("OB"), LocalDate.of(2026, 4, 30), CurrencyCode.USD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No exchange rate");
    }

    @Test
    void period_end_adjustments_extend_existing_balances() {
        accounts.add("ASS-1100-001", AccountType.ASSET, CurrencyCode.USD);
        accounts.add("LIA-2100-001", AccountType.LIABILITY, CurrencyCode.USD);
        journals.seed("J1", LocalDate.of(2026, 4, 5),
                line("ASS-1100-001", PostingDirection.DEBIT, "100.00"),
                line("LIA-2100-001", PostingDirection.CREDIT, "100.00"));
        TrialBalance base = engine.computeTrialBalance("OB", LocalDate.of(2026, 4, 30));

        var adjustment = new TrialBalanceEngine.PeriodEndAdjustment(
                "ADJ-001",
                new GlAccountCode("ASS-1100-001"), AccountType.ASSET,
                new GlAccountCode("LIA-2100-001"), AccountType.LIABILITY,
                new BigDecimal("50.00"),
                CurrencyCode.USD, "Accrual"
        );

        var adjusted = engine.applyPeriodEndAdjustments(base, List.of(adjustment));

        var byCode = new HashMap<String, TrialBalance.Row>();
        for (var r : adjusted.byCurrency().get(CurrencyCode.USD)) byCode.put(r.account().value(), r);
        assertThat(byCode.get("ASS-1100-001").debit())
                .isEqualTo(Money.of("150.00", CurrencyCode.USD));
        assertThat(byCode.get("LIA-2100-001").credit())
                .isEqualTo(Money.of("150.00", CurrencyCode.USD));
        assertThat(adjusted.invariantHolds(CurrencyCode.USD)).isTrue();
    }

    @Test
    void period_end_adjustments_reject_zero_or_negative_amounts() {
        assertThatThrownBy(() -> new TrialBalanceEngine.PeriodEndAdjustment(
                "ADJ", new GlAccountCode("ASS-1100-001"), AccountType.ASSET,
                new GlAccountCode("LIA-2100-001"), AccountType.LIABILITY,
                BigDecimal.ZERO, CurrencyCode.USD, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static SeedLine line(String account, PostingDirection direction, String amount) {
        return line(account, direction, amount, CurrencyCode.USD);
    }

    private static SeedLine line(String account, PostingDirection direction, String amount, CurrencyCode ccy) {
        return new SeedLine(account, direction, new BigDecimal(amount), ccy);
    }

    private record SeedLine(String account, PostingDirection direction, BigDecimal amount, CurrencyCode currency) {}

    private static final class InMemoryGlAccountRepository implements GlAccountRepository {
        private final Map<String, GlAccountEntity> store = new HashMap<>();

        void add(String code, AccountType type, CurrencyCode ccy) {
            store.put(code, new GlAccountEntity(code, type, ccy, code));
        }
        void addClosed(String code, AccountType type, CurrencyCode ccy) {
            var e = new GlAccountEntity(code, type, ccy, code);
            e.close();
            store.put(code, e);
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

        @Override public Optional<JournalEntryEntity> findByBusinessKey(String key) {
            return Optional.ofNullable(byKey.get(key));
        }
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
