package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingRuleEngineTest {

    private RecordingPostingService postingService;
    private InMemoryGlAccountRepository accounts;
    private InMemoryJournalEntryRepository journals;
    private Clock clock;
    private PostingRuleEngine engine;

    @BeforeEach
    void setUp() {
        postingService = new RecordingPostingService();
        accounts = new InMemoryGlAccountRepository();
        journals = new InMemoryJournalEntryRepository();
        clock = Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneId.of("UTC"));
        engine = new PostingRuleEngine(postingService, accounts, journals, clock);
    }

    @Test
    void duplicate_rule_id_is_rejected() {
        var rule = stubRule("CUSTOM:DUP", 50, t -> List.of());
        engine.registerRule(rule);

        assertThatThrownBy(() -> engine.registerRule(stubRule("CUSTOM:DUP", 60, t -> List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate rule ID");
    }

    @Test
    void unregister_removes_rule_by_id() {
        engine.registerRule(stubRule("CUSTOM:GONE", 50, t -> List.of()));
        assertThat(engine.unregisterRule("CUSTOM:GONE")).isTrue();
        assertThat(engine.unregisterRule("CUSTOM:GONE")).isFalse();
    }

    @Test
    void evaluate_and_post_respects_priority_and_filters_inapplicable_rules() {
        List<String> order = new ArrayList<>();
        engine.registerRule(stubRule("LATE", 500, t -> {
            order.add("LATE");
            return List.of(buildBalancedJournal("BK-LATE"));
        }));
        engine.registerRule(stubRule("EARLY", 50, t -> {
            order.add("EARLY");
            return List.of(buildBalancedJournal("BK-EARLY"));
        }));
        engine.registerRule(skipRule("SKIPPED"));

        var trigger = PostingRuleEngine.RuleTrigger.manual("OB", LocalDate.of(2026, 4, 30), "ref");
        var posted = engine.evaluateAndPost(trigger);

        assertThat(order).containsExactly("EARLY", "LATE");
        assertThat(posted).hasSize(2);
        assertThat(postingService.posted).hasSize(2);
    }

    @Test
    void evaluate_and_post_skips_duplicate_business_key() {
        journals.markPosted("BK-DUP", 99L);
        engine.registerRule(stubRule("ONE", 100,
                t -> List.of(buildBalancedJournal("BK-DUP"))));

        var trigger = PostingRuleEngine.RuleTrigger.manual("OB", LocalDate.of(2026, 4, 30), "x");
        var posted = engine.evaluateAndPost(trigger);

        assertThat(posted).isEmpty();
        assertThat(postingService.posted).isEmpty();
    }

    @Test
    void evaluate_continues_when_one_rule_throws() {
        engine.registerRule(stubRule("BOOM", 100, t -> { throw new RuntimeException("kaboom"); }));
        engine.registerRule(stubRule("OK", 200,
                t -> List.of(buildBalancedJournal("BK-OK"))));

        var trigger = PostingRuleEngine.RuleTrigger.manual("OB", LocalDate.of(2026, 4, 30), "x");
        var posted = engine.evaluateAndPost(trigger);

        assertThat(posted).hasSize(1);
        assertThat(posted.get(0).businessKey()).isEqualTo("BK-OK");
    }

    @Test
    void generate_auto_reversals_flips_direction_and_uses_next_period_date() {
        // Seed a single accrual journal under the EXP-9800-000 account.
        var accrual = journals.seedJournal("ACCRUAL-1", LocalDate.of(2026, 4, 28),
                List.of(line("EXP-9800-000", PostingDirection.DEBIT, "100.00"),
                        line("LIA-2200-000", PostingDirection.CREDIT, "100.00")));

        var reversals = engine.generateAutoReversals(YearMonth.of(2026, 4), "OB");

        assertThat(reversals).hasSize(1);
        var reversed = postingService.posted.get(0);
        assertThat(reversed.businessKey()).isEqualTo("AUTO-REV:ACCRUAL-1");
        assertThat(reversed.postingDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        // First original line was DEBIT EXP — reversal must be CREDIT EXP.
        assertThat(reversed.lines().get(0).direction()).isEqualTo(PostingDirection.CREDIT);
    }

    @Test
    void generate_auto_reversals_skips_already_reversed_accruals() {
        journals.seedJournal("ACCRUAL-2", LocalDate.of(2026, 4, 28),
                List.of(line("EXP-9800-000", PostingDirection.DEBIT, "50.00"),
                        line("LIA-2200-000", PostingDirection.CREDIT, "50.00")));
        journals.markPosted("AUTO-REV:ACCRUAL-2", 7L);

        var reversals = engine.generateAutoReversals(YearMonth.of(2026, 4), "OB");

        assertThat(reversals).isEmpty();
        assertThat(postingService.posted).isEmpty();
    }

    @Test
    void closing_entries_post_net_income_to_retained_earnings() {
        accounts.addAccount("REV-4000-001", AccountType.REVENUE, CurrencyCode.USD);
        accounts.addAccount("EXP-7000-001", AccountType.EXPENSE, CurrencyCode.USD);
        accounts.addAccount("EQU-9900-000", AccountType.EQUITY, CurrencyCode.USD);

        // Revenue $1,000 (credit balance), Expense $400 (debit balance) → net income $600.
        journals.seedJournal("REV-J1", LocalDate.of(2026, 4, 10),
                List.of(line("REV-4000-001", PostingDirection.CREDIT, "1000.00")));
        journals.seedJournal("EXP-J1", LocalDate.of(2026, 4, 12),
                List.of(line("EXP-7000-001", PostingDirection.DEBIT, "400.00")));

        var posted = engine.generateClosingEntries(YearMonth.of(2026, 4), "OB", CurrencyCode.USD);

        assertThat(posted).isPresent();
        var je = postingService.posted.get(0);
        // Last line is the retained-earnings credit for net income.
        var last = je.lines().get(je.lines().size() - 1);
        assertThat(last.account().value()).isEqualTo("EQU-9900-000");
        assertThat(last.direction()).isEqualTo(PostingDirection.CREDIT);
        assertThat(last.amount()).isEqualTo(Money.of("600.00", CurrencyCode.USD));
    }

    @Test
    void closing_entries_post_net_loss_as_debit_to_retained_earnings() {
        accounts.addAccount("REV-4000-001", AccountType.REVENUE, CurrencyCode.USD);
        accounts.addAccount("EXP-7000-001", AccountType.EXPENSE, CurrencyCode.USD);
        accounts.addAccount("EQU-9900-000", AccountType.EQUITY, CurrencyCode.USD);

        // Expense exceeds revenue → net loss of $200.
        journals.seedJournal("REV-J1", LocalDate.of(2026, 4, 10),
                List.of(line("REV-4000-001", PostingDirection.CREDIT, "300.00")));
        journals.seedJournal("EXP-J1", LocalDate.of(2026, 4, 12),
                List.of(line("EXP-7000-001", PostingDirection.DEBIT, "500.00")));

        engine.generateClosingEntries(YearMonth.of(2026, 4), "OB", CurrencyCode.USD);

        var je = postingService.posted.get(0);
        var last = je.lines().get(je.lines().size() - 1);
        assertThat(last.account().value()).isEqualTo("EQU-9900-000");
        assertThat(last.direction()).isEqualTo(PostingDirection.DEBIT);
        assertThat(last.amount()).isEqualTo(Money.of("200.00", CurrencyCode.USD));
    }

    @Test
    void closing_entries_returns_empty_when_zero_net_income() {
        accounts.addAccount("REV-4000-001", AccountType.REVENUE, CurrencyCode.USD);
        accounts.addAccount("EXP-7000-001", AccountType.EXPENSE, CurrencyCode.USD);

        journals.seedJournal("REV-J1", LocalDate.of(2026, 4, 10),
                List.of(line("REV-4000-001", PostingDirection.CREDIT, "100.00")));
        journals.seedJournal("EXP-J1", LocalDate.of(2026, 4, 12),
                List.of(line("EXP-7000-001", PostingDirection.DEBIT, "100.00")));

        assertThat(engine.generateClosingEntries(YearMonth.of(2026, 4), "OB", CurrencyCode.USD))
                .isEmpty();
        assertThat(postingService.posted).isEmpty();
    }

    @Test
    void closing_entries_skips_when_already_posted_for_period() {
        accounts.addAccount("REV-4000-001", AccountType.REVENUE, CurrencyCode.USD);
        journals.seedJournal("REV-J1", LocalDate.of(2026, 4, 10),
                List.of(line("REV-4000-001", PostingDirection.CREDIT, "500.00")));
        journals.markPosted("CLOSE:OB:2026-04:USD", 999L);

        assertThat(engine.generateClosingEntries(YearMonth.of(2026, 4), "OB", CurrencyCode.USD))
                .isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private PostingRuleEngine.PostingRule stubRule(String id, int priority,
            java.util.function.Function<PostingRuleEngine.RuleTrigger, List<JournalEntry>> fn) {
        return new PostingRuleEngine.PostingRule() {
            @Override public String ruleId() { return id; }
            @Override public int priority() { return priority; }
            @Override public boolean appliesTo(PostingRuleEngine.RuleTrigger t) { return true; }
            @Override public List<JournalEntry> evaluate(PostingRuleEngine.RuleTrigger t) {
                return fn.apply(t);
            }
        };
    }

    private PostingRuleEngine.PostingRule skipRule(String id) {
        return new PostingRuleEngine.PostingRule() {
            @Override public String ruleId() { return id; }
            @Override public int priority() { return 1; }
            @Override public boolean appliesTo(PostingRuleEngine.RuleTrigger t) { return false; }
            @Override public List<JournalEntry> evaluate(PostingRuleEngine.RuleTrigger t) {
                throw new AssertionError("must not be called");
            }
        };
    }

    private JournalEntry buildBalancedJournal(String key) {
        return new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 30), key,
                "test " + key,
                List.of(
                        PostingLine.debit(new GlAccountCode("EXP-7000-001"),
                                Money.of("10.00", CurrencyCode.USD), "test debit"),
                        PostingLine.credit(new GlAccountCode("LIA-2200-000"),
                                Money.of("10.00", CurrencyCode.USD), "test credit")
                ));
    }

    private static SeedLine line(String account, PostingDirection direction, String amount) {
        return new SeedLine(account, direction, new BigDecimal(amount), CurrencyCode.USD);
    }

    private record SeedLine(String account, PostingDirection direction,
                            BigDecimal amount, CurrencyCode currency) {}

    // ── In-memory test doubles ──────────────────────────────────────────

    private static final class RecordingPostingService implements PostingService {
        final List<PostedJournal> posted = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong(1000);

        @Override
        public PostedJournal post(JournalEntry entry) {
            var p = new PostedJournal(sequence.incrementAndGet(), entry.proposalId(),
                    entry.postingDate(), Instant.now(), entry.businessKey(),
                    entry.description(), entry.lines(), "test");
            posted.add(p);
            return p;
        }
    }

    private static final class InMemoryGlAccountRepository implements GlAccountRepository {
        private final Map<String, GlAccountEntity> store = new HashMap<>();

        void addAccount(String code, AccountType type, CurrencyCode ccy) {
            store.put(code, new GlAccountEntity(code, type, ccy, code));
        }

        @Override public Optional<GlAccountEntity> findById(String code) {
            return Optional.ofNullable(store.get(code));
        }
        @Override public List<GlAccountEntity> findAll() { return new ArrayList<>(store.values()); }
        @Override public boolean existsById(String s) { return store.containsKey(s); }
        @Override public long count() { return store.size(); }
        @Override public <S extends GlAccountEntity> S save(S e) { throw u(); }
        @Override public <S extends GlAccountEntity> List<S> saveAll(Iterable<S> es) { throw u(); }
        @Override public List<GlAccountEntity> findAllById(Iterable<String> ids) { throw u(); }
        @Override public void deleteById(String s) { throw u(); }
        @Override public void delete(GlAccountEntity entity) { throw u(); }
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
        private final Map<String, JournalEntryEntity> byKey = new HashMap<>();
        private final List<JournalEntryEntity> all = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        JournalEntryEntity seedJournal(String businessKey, LocalDate postingDate, List<SeedLine> lines) {
            var entity = new JournalEntryEntity(UUID.randomUUID(), postingDate,
                    Instant.now(), businessKey, businessKey + " seeded for tests");
            for (SeedLine l : lines) {
                entity.addLine(new PostingLineEntity(l.account(), l.direction(),
                        l.amount(), l.currency(), "seeded"));
            }
            assignSequence(entity, seq.getAndIncrement());
            byKey.put(businessKey, entity);
            all.add(entity);
            return entity;
        }

        void markPosted(String businessKey, long sequence) {
            var entity = new JournalEntryEntity(UUID.randomUUID(), LocalDate.now(),
                    Instant.now(), businessKey, "marker");
            assignSequence(entity, sequence);
            byKey.put(businessKey, entity);
        }

        private static void assignSequence(JournalEntryEntity e, long s) {
            try {
                var f = JournalEntryEntity.class.getDeclaredField("sequence");
                f.setAccessible(true);
                f.set(e, s);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
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
