package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InterestCalculationEngineTest {

    private static final String ACCT = "OB-C-ABCD1234";

    private StubLedgerQueries ledger;
    private RecordingPostingService posting;
    private StubConsumerAccountRepository repo;
    private Clock clock;
    private InterestCalculationEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new StubLedgerQueries();
        posting = new RecordingPostingService();
        repo = new StubConsumerAccountRepository();
        // Mid-month date so DAILY compounding posts but MONTHLY does not.
        clock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneId.of("UTC"));
        engine = new InterestCalculationEngine(repo, ledger, posting, clock);
    }

    @Test
    void zero_balance_yields_no_interest_and_no_post() {
        var account = newAccount(ConsumerProduct.SAVINGS_STANDARD);
        ledger.balance = Money.zero(CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 15));

        assertThat(result.grossInterest().isZero()).isTrue();
        assertThat(result.posted()).isFalse();
        assertThat(posting.postings).isEmpty();
    }

    @Test
    void standard_savings_accrues_one_day_at_base_apr() {
        var account = newAccount(ConsumerProduct.SAVINGS_STANDARD); // 45 bps
        ledger.balance = Money.of("10000.00", CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 15));

        // 10000 * 0.0045 / 365 ≈ 0.1233; Money rounds to cents → $0.12
        assertThat(result.grossInterest()).isEqualTo(Money.of("0.12", CurrencyCode.USD));
        assertThat(result.netInterest()).isEqualTo(result.grossInterest());
        assertThat(result.posted()).isTrue(); // savings compounds DAILY
        assertThat(posting.postings).hasSize(1);
    }

    @Test
    void cd_accrues_actual_360_and_does_not_post_mid_month() {
        var account = newCd(ConsumerProduct.CD_12M); // 500 bps, ACT/360
        ledger.balance = Money.of("10000.00", CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 15));

        // 10000 * 0.05 / 360 ≈ 1.3889; Money rounds to cents → $1.39
        assertThat(result.grossInterest()).isEqualTo(Money.of("1.39", CurrencyCode.USD));
        assertThat(result.posted()).isFalse(); // CD compounds MONTHLY; April 15 isn't a boundary
        assertThat(posting.postings).isEmpty();
    }

    @Test
    void cd_posts_when_today_is_first_of_month() {
        var account = newCd(ConsumerProduct.CD_12M);
        ledger.balance = Money.of("10000.00", CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 1));

        assertThat(result.posted()).isTrue();
        assertThat(posting.postings).hasSize(1);
    }

    @Test
    void high_yield_savings_uses_top_tier_for_balance_above_max_breakpoint() {
        var account = newAccount(ConsumerProduct.SAVINGS_HIGH_YIELD);
        // Balance well above the top breakpoint ($250k → 475 bps).
        ledger.balance = Money.of("500000.00", CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 15));

        // Tiered total of one day on $500k must exceed flat top-rate on $500k? No —
        // each tier's slice is taxed separately, so total < flat-top-rate. Sanity
        // check: total interest must be positive and bounded by flat top-rate.
        BigDecimal flatTopRate = new BigDecimal("500000")
                .multiply(new BigDecimal("0.0475"))
                .divide(new BigDecimal("365"), 6, RoundingMode.HALF_EVEN);
        assertThat(result.grossInterest().amount()).isPositive();
        assertThat(result.grossInterest().amount()).isLessThanOrEqualTo(flatTopRate);
    }

    @Test
    void high_yield_balance_below_first_tier_uses_first_tier_rate() {
        var account = newAccount(ConsumerProduct.SAVINGS_HIGH_YIELD);
        ledger.balance = Money.of("5000.00", CurrencyCode.USD);

        var result = engine.calculateAndAccrue(account,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 15));

        // 5000 * 0.0425 / 365 ≈ 0.5822; Money rounds to cents → $0.58
        assertThat(result.grossInterest()).isEqualTo(Money.of("0.58", CurrencyCode.USD));
    }

    @Test
    void daily_accrual_skips_checking_and_non_open_accounts() {
        var checking = newAccount(ConsumerProduct.CHECKING_BASIC);
        checking.activate();
        var openSavings = newAccount(ConsumerProduct.SAVINGS_STANDARD);
        openSavings.activate();
        var frozen = newAccount(ConsumerProduct.SAVINGS_STANDARD, "OB-S-FROZEN12");
        frozen.activate();
        frozen.freeze("compliance");
        repo.add(checking);
        repo.add(openSavings);
        repo.add(frozen);
        ledger.balance = Money.of("1000.00", CurrencyCode.USD);

        var results = engine.runDailyAccrual();

        // Only the open savings should have been processed.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).accountNumber()).isEqualTo(openSavings.accountNumber());
    }

    private ConsumerAccountEntity newAccount(ConsumerProduct product) {
        return newAccount(product, ACCT);
    }

    private ConsumerAccountEntity newAccount(ConsumerProduct product, String acct) {
        return new ConsumerAccountEntity(acct, UUID.randomUUID(), product,
                CurrencyCode.USD, LocalDate.of(2026, 1, 1), null);
    }

    private ConsumerAccountEntity newCd(ConsumerProduct product) {
        return new ConsumerAccountEntity(ACCT, UUID.randomUUID(), product,
                CurrencyCode.USD, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    }

    private static final class StubLedgerQueries implements LedgerQueries {
        Money balance = Money.zero(CurrencyCode.USD);

        @Override public Money currentBalance(GlAccountCode code) { return balance; }
        @Override public Money balanceAsOf(GlAccountCode code, LocalDate asOf) { return balance; }
        @Override public List<PostedJournal> journalHistory(GlAccountCode code, LocalDate from, LocalDate to) { return List.of(); }
        @Override public TrialBalance trialBalance(LocalDate asOf) { return new TrialBalance(asOf, java.util.Map.of()); }
    }

    private static final class RecordingPostingService implements PostingService {
        final List<JournalEntry> postings = new ArrayList<>();

        @Override
        public PostedJournal post(JournalEntry entry) {
            postings.add(entry);
            return new PostedJournal(postings.size(), entry.proposalId(),
                    entry.postingDate(), Instant.now(), entry.businessKey(),
                    entry.description(), entry.lines(), "test");
        }
    }

    private static final class StubConsumerAccountRepository implements ConsumerAccountRepository {
        private final List<ConsumerAccountEntity> all = new ArrayList<>();

        void add(ConsumerAccountEntity e) { all.add(e); }

        @Override public <S extends ConsumerAccountEntity> S save(S entity) { all.add(entity); return entity; }
        @Override public <S extends ConsumerAccountEntity> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            entities.forEach(e -> { all.add(e); saved.add(e); });
            return saved;
        }
        @Override public Optional<ConsumerAccountEntity> findById(String id) {
            return all.stream().filter(e -> e.accountNumber().equals(id)).findFirst();
        }
        @Override public boolean existsById(String id) { return findById(id).isPresent(); }
        @Override public List<ConsumerAccountEntity> findAll() { return new ArrayList<>(all); }
        @Override public List<ConsumerAccountEntity> findAllById(Iterable<String> ids) { throw u(); }
        @Override public long count() { return all.size(); }
        @Override public void deleteById(String id) { throw u(); }
        @Override public void delete(ConsumerAccountEntity entity) { throw u(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw u(); }
        @Override public void deleteAll(Iterable<? extends ConsumerAccountEntity> entities) { throw u(); }
        @Override public void deleteAll() { all.clear(); }
        @Override public List<ConsumerAccountEntity> findAll(org.springframework.data.domain.Sort sort) { throw u(); }
        @Override public org.springframework.data.domain.Page<ConsumerAccountEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw u(); }
        @Override public void flush() {}
        @Override public <S extends ConsumerAccountEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends ConsumerAccountEntity> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public void deleteAllInBatch(Iterable<ConsumerAccountEntity> entities) { throw u(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw u(); }
        @Override public void deleteAllInBatch() { throw u(); }
        @Override public ConsumerAccountEntity getOne(String id) { throw u(); }
        @Override public ConsumerAccountEntity getById(String id) { throw u(); }
        @Override public ConsumerAccountEntity getReferenceById(String id) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort sort) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable pageable) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> long count(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw u(); }
        @Override public <S extends ConsumerAccountEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) { throw u(); }
        @Override public <S extends ConsumerAccountEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw u(); }

        private static UnsupportedOperationException u() {
            return new UnsupportedOperationException("Not used");
        }
    }
}
