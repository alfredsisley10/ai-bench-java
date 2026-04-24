package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountFeeEngineTest {

    private static final String ACCT = "OB-C-ABCD1234";

    private RecordingEventBus eventBus;
    private RecordingPostingService posting;
    private StubLedgerQueries ledger;
    private StubConsumerAccountRepository repo;
    private Clock clock;
    private AccountFeeEngine engine;

    @BeforeEach
    void setUp() {
        eventBus = new RecordingEventBus();
        posting = new RecordingPostingService();
        ledger = new StubLedgerQueries();
        repo = new StubConsumerAccountRepository();
        clock = Clock.fixed(Instant.parse("2026-04-01T04:00:00Z"), ZoneId.of("UTC"));
        engine = new AccountFeeEngine(repo, ledger, posting, eventBus, clock);
    }

    @Test
    void maintenance_fee_waived_when_balance_meets_threshold() {
        var account = makeAccount(ConsumerProduct.CHECKING_BASIC);
        ledger.setBalance(Money.of("2000.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var maintenance = pickByType(actions, "MONTHLY_MAINTENANCE");
        assertThat(maintenance).isInstanceOf(AccountFeeEngine.FeeAction.FeeWaived.class);
    }

    @Test
    void maintenance_fee_assessed_when_balance_below_threshold() {
        var account = makeAccount(ConsumerProduct.CHECKING_BASIC);
        ledger.setBalance(Money.of("500.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var maintenance = pickByType(actions, "MONTHLY_MAINTENANCE");
        assertThat(maintenance).isInstanceOf(AccountFeeEngine.FeeAction.FeeAssessed.class);
        var assessed = (AccountFeeEngine.FeeAction.FeeAssessed) maintenance;
        assertThat(assessed.amount()).isEqualTo(Money.of("12.00", CurrencyCode.USD));
        assertThat(posting.postings).hasSize(1);
    }

    @Test
    void high_yield_savings_has_no_maintenance_fee() {
        var account = makeAccount(ConsumerProduct.SAVINGS_HIGH_YIELD);
        ledger.setBalance(Money.of("100.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var maintenance = pickByType(actions, "MONTHLY_MAINTENANCE");
        assertThat(maintenance).isInstanceOf(AccountFeeEngine.FeeAction.NoFeeApplicable.class);
    }

    @Test
    void minimum_balance_fee_assessed_below_threshold() {
        var account = makeAccount(ConsumerProduct.SAVINGS_STANDARD);
        ledger.setBalance(Money.of("50.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var minBalance = pickByType(actions, "MINIMUM_BALANCE");
        assertThat(minBalance).isInstanceOf(AccountFeeEngine.FeeAction.FeeAssessed.class);
    }

    @Test
    void minimum_balance_fee_skipped_when_above_threshold() {
        var account = makeAccount(ConsumerProduct.SAVINGS_STANDARD);
        ledger.setBalance(Money.of("250.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var minBalance = pickByType(actions, "MINIMUM_BALANCE");
        assertThat(minBalance).isInstanceOf(AccountFeeEngine.FeeAction.NoFeeApplicable.class);
    }

    @Test
    void paper_statement_fee_noop_when_enrolled_in_estatements() {
        // Engine defaults to e-statement enrolled; paper fee should be NoFeeApplicable.
        var account = makeAccount(ConsumerProduct.CHECKING_BASIC);
        ledger.setBalance(Money.of("500.00", CurrencyCode.USD));

        var actions = engine.assessFeesForAccount(account, LocalDate.of(2026, 4, 1));

        var paper = pickByType(actions, "PAPER_STATEMENT");
        assertThat(paper).isInstanceOf(AccountFeeEngine.FeeAction.NoFeeApplicable.class);
    }

    @Test
    void reverse_fee_posts_reversal_and_publishes_event() {
        var entity = makeAccount(ConsumerProduct.CHECKING_BASIC);
        repo.add(entity);

        var reversal = engine.reverseFee(AccountNumber.of(ACCT),
                "MONTHLY_MAINTENANCE", Money.of("12.00", CurrencyCode.USD),
                "goodwill gesture", "mgr1");

        assertThat(reversal.approvedBy()).isEqualTo("mgr1");
        assertThat(reversal.reversalReason()).isEqualTo("goodwill gesture");
        assertThat(posting.postings).hasSize(1);
        assertThat(eventBus.events).anyMatch(e -> e instanceof AccountFeeEngine.FeeEvent);
    }

    @Test
    void reverse_fee_unknown_account_throws() {
        assertThatThrownBy(() -> engine.reverseFee(AccountNumber.of(ACCT),
                "MONTHLY_MAINTENANCE", Money.of("12.00", CurrencyCode.USD),
                "reason", "mgr1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthly_assessment_batch_processes_all_open_non_maturing_accounts() {
        var checking = makeAccount(ConsumerProduct.CHECKING_BASIC);
        repo.add(checking);
        ledger.setBalance(Money.of("500.00", CurrencyCode.USD));

        var actions = engine.runMonthlyFeeAssessment();
        assertThat(actions).isNotEmpty();
        assertThat(actions.stream().anyMatch(a -> a instanceof AccountFeeEngine.FeeAction.FeeAssessed)).isTrue();
    }

    @Test
    void monthly_assessment_skips_closed_accounts() {
        var open = makeAccount(ConsumerProduct.CHECKING_BASIC);
        var closed = makeAccount(ConsumerProduct.CHECKING_BASIC, "OB-C-CLOSED00");
        closed.close(Instant.parse("2026-03-01T00:00:00Z"));
        repo.add(open);
        repo.add(closed);
        ledger.setBalance(Money.of("500.00", CurrencyCode.USD));

        var actions = engine.runMonthlyFeeAssessment();

        assertThat(actions.stream()
                .filter(a -> a instanceof AccountFeeEngine.FeeAction.FeeAssessed fa
                        && fa.account().raw().equals("OB-C-CLOSED00")))
                .isEmpty();
    }

    private ConsumerAccountEntity makeAccount(ConsumerProduct product) {
        return makeAccount(product, ACCT);
    }

    private ConsumerAccountEntity makeAccount(ConsumerProduct product, String acct) {
        var entity = new ConsumerAccountEntity(acct, UUID.randomUUID(), product,
                CurrencyCode.USD, LocalDate.of(2026, 1, 1), null);
        entity.activate();
        return entity;
    }

    private AccountFeeEngine.FeeAction pickByType(List<AccountFeeEngine.FeeAction> actions, String type) {
        return actions.stream()
                .filter(a -> switch (a) {
                    case AccountFeeEngine.FeeAction.FeeAssessed x -> x.feeType().equals(type);
                    case AccountFeeEngine.FeeAction.FeeWaived x -> x.feeType().equals(type);
                    case AccountFeeEngine.FeeAction.NoFeeApplicable x -> x.feeType().equals(type);
                    case AccountFeeEngine.FeeAction.FeeReversed x -> x.feeType().equals(type);
                })
                .findFirst().orElseThrow();
    }

    private static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
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

    private static final class StubLedgerQueries implements LedgerQueries {
        private Money balance = Money.zero(CurrencyCode.USD);

        void setBalance(Money balance) { this.balance = balance; }

        @Override
        public Money currentBalance(GlAccountCode code) {
            return balance;
        }

        @Override
        public Money balanceAsOf(GlAccountCode code, LocalDate asOf) {
            return balance;
        }

        @Override
        public List<PostedJournal> journalHistory(GlAccountCode code, LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public TrialBalance trialBalance(LocalDate asOf) {
            return new TrialBalance(asOf, java.util.Map.of());
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
        @Override public java.util.Optional<ConsumerAccountEntity> findById(String id) {
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
        @Override public <S extends ConsumerAccountEntity> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw u(); }

        private static UnsupportedOperationException u() {
            return new UnsupportedOperationException("Not used");
        }
    }
}
