package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.internal.JournalEntryValidator.Severity;
import com.omnibank.ledger.internal.JournalEntryValidator.ValidationContext;
import com.omnibank.ledger.internal.JournalEntryValidator.ValidationError;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JournalEntryValidatorTest {

    private static final GlAccountCode CASH = new GlAccountCode("ASS-1100-001");
    private static final GlAccountCode DEPOSITS = new GlAccountCode("LIA-2100-001");
    private static final GlAccountCode RETAINED = new GlAccountCode("EQU-9900-000");
    private static final GlAccountCode SUSPICIOUS = new GlAccountCode("ASS-8001-111");

    private InMemoryGlAccountRepository accounts;
    private InMemoryJournalEntryRepository journals;
    private Clock clock;
    private JournalEntryValidator validator;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryGlAccountRepository();
        journals = new InMemoryJournalEntryRepository();
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        validator = new JournalEntryValidator(accounts, journals, clock);

        accounts.addAccount(CASH.value(), AccountType.ASSET, CurrencyCode.USD, false);
        accounts.addAccount(DEPOSITS.value(), AccountType.LIABILITY, CurrencyCode.USD, false);
        accounts.addAccount(RETAINED.value(), AccountType.EQUITY, CurrencyCode.USD, false);
        accounts.addAccount(SUSPICIOUS.value(), AccountType.ASSET, CurrencyCode.USD, false);
    }

    @Test
    void balanced_entry_with_valid_accounts_passes_validation() {
        var entry = balancedUsd(100);
        var errors = validator.validate(entry);
        assertThat(errors.stream().filter(ValidationError::isError)).isEmpty();
    }

    @Test
    void unbalanced_entry_reports_unbalanced_error() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-UNBAL", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(99, CurrencyCode.USD), "liability")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("UNBALANCED");
    }

    @Test
    void future_posting_date_rejected_in_normal_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(),
                LocalDate.of(2027, 1, 1),
                "K-FUTURE", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("FUTURE_DATE");
    }

    @Test
    void future_posting_date_allowed_in_system_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2027, 1, 1),
                "K-FUTURE-SYS", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry, ValidationContext.SYSTEM);
        assertThat(codesOf(errors)).doesNotContain("FUTURE_DATE");
    }

    @Test
    void excessive_backdate_rejected_in_normal_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2025, 1, 1),
                "K-BACKDATE", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("EXCESSIVE_BACKDATE");
    }

    @Test
    void backdate_warning_in_adjustment_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2025, 1, 1),
                "K-BACKDATE-ADJ", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry, ValidationContext.ADJUSTMENT);
        assertThat(codesOf(errors)).contains("BACKDATE_WARNING");
    }

    @Test
    void unknown_account_reports_error() {
        var unknown = new GlAccountCode("ASS-9999-999");
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-UNK", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(unknown, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("UNKNOWN_ACCOUNT");
    }

    @Test
    void closed_account_reports_error() {
        accounts.addAccount("ASS-1100-999", AccountType.ASSET, CurrencyCode.USD, true);
        var closed = new GlAccountCode("ASS-1100-999");
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-CLOSED", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(closed, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("ACCOUNT_CLOSED");
    }

    @Test
    void currency_mismatch_reports_error() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-CCY", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.EUR), "eur"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.EUR), "eur")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("CURRENCY_MISMATCH", "CURRENCY_MISMATCH");
    }

    @Test
    void duplicate_business_key_reports_error() {
        journals.registerPosted("K-DUP", 4242L);
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-DUP", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("DUPLICATE_BUSINESS_KEY");
    }

    @Test
    void dual_auth_threshold_emits_warning() {
        var big = Money.of(new BigDecimal("20000000.00"), CurrencyCode.USD);
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-BIG", "Very large intraday settlement transfer for audit",
                List.of(
                        PostingLine.debit(CASH, big, "cash"),
                        PostingLine.credit(DEPOSITS, big, "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("DUAL_AUTH_REQUIRED");
        assertThat(errors.stream().filter(e -> e.code().equals("DUAL_AUTH_REQUIRED"))
                .allMatch(e -> e.severity() == Severity.WARNING)).isTrue();
    }

    @Test
    void restricted_retained_earnings_account_reports_error_in_normal_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-RE", "Direct manual to retained earnings audit",
                List.of(
                        PostingLine.debit(RETAINED, Money.of(100, CurrencyCode.USD), "re"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("RESTRICTED_ACCOUNT");
    }

    @Test
    void restricted_retained_earnings_skipped_in_system_context() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-RE-SYS", "Period-close retained earnings booking for audit",
                List.of(
                        PostingLine.debit(RETAINED, Money.of(100, CurrencyCode.USD), "re"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry, ValidationContext.SYSTEM);
        assertThat(codesOf(errors)).doesNotContain("RESTRICTED_ACCOUNT");
    }

    @Test
    void suspicious_activity_account_emits_regulatory_warning() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-SUSP", "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(SUSPICIOUS, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("REGULATORY_HOLD");
    }

    @Test
    void short_description_emits_warning() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-SHORT", "brief",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("SHORT_DESCRIPTION");
    }

    @Test
    void missing_memo_on_high_value_line_emits_warning() {
        var big = Money.of(new BigDecimal("200000.00"), CurrencyCode.USD);
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-MEMO", "High value line missing memo audit case",
                List.of(
                        PostingLine.debit(CASH, big, null),
                        PostingLine.credit(DEPOSITS, big, "liability offset")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("MISSING_MEMO_HIGH_VALUE");
    }

    @Test
    void weekend_posting_emits_warning() {
        var entry = new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 11),
                "K-WKEND", "Saturday booking needed for audit",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "cash"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "liab")
                ));
        var errors = validator.validate(entry);
        assertThat(codesOf(errors)).contains("WEEKEND_POSTING");
    }

    private JournalEntry balancedUsd(long amount) {
        return new JournalEntry(
                UUID.randomUUID(), LocalDate.of(2026, 4, 15),
                "K-" + amount,
                "Customer cash deposit for audit trail",
                List.of(
                        PostingLine.debit(CASH, Money.of(amount, CurrencyCode.USD), "counter-deposit"),
                        PostingLine.credit(DEPOSITS, Money.of(amount, CurrencyCode.USD), "dda-liability")
                ));
    }

    private List<String> codesOf(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::code).toList();
    }

    private static final class InMemoryGlAccountRepository implements GlAccountRepository {
        private final Map<String, GlAccountEntity> store = new HashMap<>();

        void addAccount(String code, AccountType type, CurrencyCode ccy, boolean closed) {
            var entity = new GlAccountEntity(code, type, ccy, code);
            if (closed) entity.close();
            store.put(code, entity);
        }

        @Override
        public Optional<GlAccountEntity> findById(String code) {
            return Optional.ofNullable(store.get(code));
        }

        // JpaRepository methods we don't use — throw if called (we only need findById).
        @Override public <S extends GlAccountEntity> S save(S entity) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> List<S> saveAll(Iterable<S> entities) { throw unsupported(); }
        @Override public boolean existsById(String s) { return store.containsKey(s); }
        @Override public List<GlAccountEntity> findAll() { return new ArrayList<>(store.values()); }
        @Override public List<GlAccountEntity> findAllById(Iterable<String> strings) { throw unsupported(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(String s) { throw unsupported(); }
        @Override public void delete(GlAccountEntity entity) { throw unsupported(); }
        @Override public void deleteAllById(Iterable<? extends String> strings) { throw unsupported(); }
        @Override public void deleteAll(Iterable<? extends GlAccountEntity> entities) { throw unsupported(); }
        @Override public void deleteAll() { throw unsupported(); }
        @Override public List<GlAccountEntity> findAll(org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public org.springframework.data.domain.Page<GlAccountEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public void flush() { throw unsupported(); }
        @Override public <S extends GlAccountEntity> S saveAndFlush(S entity) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
        @Override public void deleteAllInBatch(Iterable<GlAccountEntity> entities) { throw unsupported(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> strings) { throw unsupported(); }
        @Override public void deleteAllInBatch() { throw unsupported(); }
        @Override public GlAccountEntity getOne(String s) { throw unsupported(); }
        @Override public GlAccountEntity getById(String s) { throw unsupported(); }
        @Override public GlAccountEntity getReferenceById(String s) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> long count(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends GlAccountEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }
        @Override public <S extends GlAccountEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Not used by validator tests");
        }
    }

    private static final class InMemoryJournalEntryRepository implements JournalEntryRepository {
        private final Map<String, Long> posted = new HashMap<>();

        void registerPosted(String businessKey, long sequence) {
            posted.put(businessKey, sequence);
        }

        @Override
        public Optional<JournalEntryEntity> findByBusinessKey(String businessKey) {
            var seq = posted.get(businessKey);
            if (seq == null) return Optional.empty();
            var entity = new JournalEntryEntity(UUID.randomUUID(), LocalDate.now(),
                    Instant.now(), businessKey, "existing entry audit record");
            try {
                var field = JournalEntryEntity.class.getDeclaredField("sequence");
                field.setAccessible(true);
                field.set(entity, seq);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return Optional.of(entity);
        }

        // JpaRepository methods we don't use
        @Override public <S extends JournalEntryEntity> S save(S entity) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> List<S> saveAll(Iterable<S> entities) { throw unsupported(); }
        @Override public Optional<JournalEntryEntity> findById(Long aLong) { throw unsupported(); }
        @Override public boolean existsById(Long aLong) { throw unsupported(); }
        @Override public List<JournalEntryEntity> findAll() { throw unsupported(); }
        @Override public List<JournalEntryEntity> findAllById(Iterable<Long> longs) { throw unsupported(); }
        @Override public long count() { return posted.size(); }
        @Override public void deleteById(Long aLong) { throw unsupported(); }
        @Override public void delete(JournalEntryEntity entity) { throw unsupported(); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { throw unsupported(); }
        @Override public void deleteAll(Iterable<? extends JournalEntryEntity> entities) { throw unsupported(); }
        @Override public void deleteAll() { throw unsupported(); }
        @Override public List<JournalEntryEntity> findAll(org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public org.springframework.data.domain.Page<JournalEntryEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public void flush() { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> S saveAndFlush(S entity) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
        @Override public void deleteAllInBatch(Iterable<JournalEntryEntity> entities) { throw unsupported(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { throw unsupported(); }
        @Override public void deleteAllInBatch() { throw unsupported(); }
        @Override public JournalEntryEntity getOne(Long aLong) { throw unsupported(); }
        @Override public JournalEntryEntity getById(Long aLong) { throw unsupported(); }
        @Override public JournalEntryEntity getReferenceById(Long aLong) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> long count(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }
        @Override public <S extends JournalEntryEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public List<JournalEntryEntity> findJournalsForAccount(String account, LocalDate from, LocalDate to) { return List.of(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Not used by validator tests");
        }
    }
}
