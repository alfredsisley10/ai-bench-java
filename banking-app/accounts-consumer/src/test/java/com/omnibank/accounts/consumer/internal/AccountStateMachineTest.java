package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountStateMachineTest {

    private static final String ACCT = "OB-C-ABCD1234";

    private RecordingEventBus eventBus;
    private StubConsumerAccountRepository repo;
    private Clock clock;
    private AccountStateMachine machine;

    @BeforeEach
    void setUp() {
        eventBus = new RecordingEventBus();
        repo = new StubConsumerAccountRepository();
        clock = Clock.fixed(Instant.parse("2026-04-17T12:00:00Z"), ZoneId.of("UTC"));
        machine = new AccountStateMachine(repo, eventBus, clock);
    }

    @Test
    void pending_to_open_is_legal_and_emits_event() {
        repo.add(newAccount(ACCT, ConsumerProduct.CHECKING_BASIC));

        var result = machine.transition(
                AccountNumber.of(ACCT), AccountStatus.OPEN, "KYC complete", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Success.class);
        var success = (AccountStateMachine.TransitionResult.Success) result;
        assertThat(success.from()).isEqualTo(AccountStatus.PENDING);
        assertThat(success.to()).isEqualTo(AccountStatus.OPEN);
        assertThat(repo.findById(ACCT).orElseThrow().status()).isEqualTo(AccountStatus.OPEN);
        assertThat(eventBus.events).hasSize(1);
        assertThat(eventBus.events.get(0).eventType()).isEqualTo("accounts.consumer.state_changed");
    }

    @Test
    void illegal_transition_is_rejected_without_state_change() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        repo.add(entity);

        // PENDING -> FROZEN is not in the legal set.
        var result = machine.transition(
                AccountNumber.of(ACCT), AccountStatus.FROZEN, "bogus", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Rejected.class);
        assertThat(((AccountStateMachine.TransitionResult.Rejected) result).reason())
                .contains("not permitted");
        assertThat(entity.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(eventBus.events).isEmpty();
    }

    @Test
    void closed_is_terminal_and_cannot_be_left() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        entity.close(Instant.parse("2026-03-01T00:00:00Z"));
        repo.add(entity);

        var result = machine.transition(
                AccountNumber.of(ACCT), AccountStatus.OPEN, "reopen", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Rejected.class);
    }

    @Test
    void freeze_records_reason_and_transitions_to_frozen() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        repo.add(entity);

        var result = machine.freeze(AccountNumber.of(ACCT),
                AccountStateMachine.FreezeReason.OFAC_MATCH, "CASE-42", "compliance-bot");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Success.class);
        assertThat(entity.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(entity.freezeReason()).contains("CASE-42").contains("OFAC");
    }

    @Test
    void unfreeze_requires_substantive_resolution_notes() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        entity.freeze("prior case");
        repo.add(entity);

        var rejected = machine.unfreeze(AccountNumber.of(ACCT), "ok", "agent-1");

        assertThat(rejected).isInstanceOf(AccountStateMachine.TransitionResult.Rejected.class);
        assertThat(((AccountStateMachine.TransitionResult.Rejected) rejected).reason())
                .contains("20 characters");
        assertThat(entity.status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void unfreeze_with_full_notes_returns_account_to_open() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        entity.freeze("prior case");
        repo.add(entity);

        var result = machine.unfreeze(AccountNumber.of(ACCT),
                "Resolved after investigation concluded with no findings.", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Success.class);
        assertThat(entity.status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    void cd_cannot_close_before_maturity() {
        var entity = new ConsumerAccountEntity(ACCT, UUID.randomUUID(),
                ConsumerProduct.CD_12M, CurrencyCode.USD,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        entity.activate();
        repo.add(entity);

        var result = machine.transition(AccountNumber.of(ACCT),
                AccountStatus.CLOSED, "customer request", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Rejected.class);
        assertThat(((AccountStateMachine.TransitionResult.Rejected) result).reason())
                .contains("maturity");
        assertThat(entity.status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    void cd_may_close_after_maturity_date() {
        // clock is 2026-04-17; CD matures 2026-03-01 (past).
        var entity = new ConsumerAccountEntity(ACCT, UUID.randomUUID(),
                ConsumerProduct.CD_6M, CurrencyCode.USD,
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 3, 1));
        entity.activate();
        repo.add(entity);

        var result = machine.transition(AccountNumber.of(ACCT),
                AccountStatus.CLOSED, "matured", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Success.class);
        assertThat(entity.status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void reactivate_rejects_non_dormant_account() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        repo.add(entity);

        var result = machine.reactivate(AccountNumber.of(ACCT), "mobile login", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Rejected.class);
    }

    @Test
    void reactivate_from_dormant_returns_to_open() {
        var entity = newAccount(ACCT, ConsumerProduct.CHECKING_BASIC);
        entity.activate();
        entity.freeze("DORMANT: inactivity");
        // entity.freeze sets status to FROZEN; force DORMANT via another transition pass.
        // Easier: directly use the state machine's transition OPEN->DORMANT.
        entity.activate();
        repo.add(entity);
        machine.transition(AccountNumber.of(ACCT), AccountStatus.DORMANT,
                "inactivity", "system");
        eventBus.events.clear();

        var result = machine.reactivate(AccountNumber.of(ACCT), "mobile login", "agent-1");

        assertThat(result).isInstanceOf(AccountStateMachine.TransitionResult.Success.class);
        assertThat(entity.status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    void unknown_account_raises_illegal_argument() {
        assertThatThrownBy(() -> machine.transition(
                AccountNumber.of("OB-C-ZZZZZZZZ"), AccountStatus.OPEN, "x", "y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void required_parameters_are_validated() {
        assertThatThrownBy(() -> machine.transition(null, AccountStatus.OPEN, "x", "y"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> machine.transition(AccountNumber.of(ACCT), null, "x", "y"))
                .isInstanceOf(NullPointerException.class);
    }

    private ConsumerAccountEntity newAccount(String acct, ConsumerProduct product) {
        return new ConsumerAccountEntity(acct, UUID.randomUUID(), product,
                CurrencyCode.USD, LocalDate.of(2026, 1, 1), null);
    }

    private static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) { events.add(event); }
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
