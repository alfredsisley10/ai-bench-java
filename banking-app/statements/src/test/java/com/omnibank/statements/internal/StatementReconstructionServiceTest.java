package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.statements.internal.StatementGenerator.HoldSummary;
import com.omnibank.statements.internal.StatementGenerator.LineType;
import com.omnibank.statements.internal.StatementReconstructionService.AccountContext;
import com.omnibank.statements.internal.StatementReconstructionService.AuditTrail;
import com.omnibank.statements.internal.StatementReconstructionService.LedgerPosting;
import com.omnibank.statements.internal.StatementReconstructionService.LedgerSource;
import com.omnibank.statements.internal.StatementReconstructionService.ReconstructionResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementReconstructionServiceTest {

    private Clock clock;
    private StatementGenerator generator;
    private FakeLedger ledger;
    private StatementReconstructionService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);
        generator = new StatementGenerator(clock);
        ledger = new FakeLedger();
        service = new StatementReconstructionService(clock, generator, ledger);
    }

    private AccountContext context() {
        return new AccountContext(
                StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                "Alice Example",
                "123 Main St",
                "Free Checking",
                CurrencyCode.USD);
    }

    @Test
    void reconstruct_computes_closing_balance_from_postings() {
        ledger.opening = Money.of(1000, CurrencyCode.USD);
        ledger.postings.add(new LedgerPosting("REF-1", LocalDate.of(2026, 4, 3),
                Money.of(500, CurrencyCode.USD), "dep", LineType.DEPOSIT));
        ledger.postings.add(new LedgerPosting("REF-2", LocalDate.of(2026, 4, 10),
                Money.of(200, CurrencyCode.USD), "wd", LineType.WITHDRAWAL));

        ReconstructionResult result = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "csr-22", "customer dispute");

        assertThat(result.content().summary().closingBalance())
                .isEqualTo(Money.of(1300, CurrencyCode.USD));
        assertThat(result.auditTrail().requestedBy()).isEqualTo("csr-22");
        assertThat(result.auditTrail().reason()).isEqualTo("customer dispute");
        assertThat(result.auditTrail().postingsConsumed()).isEqualTo(2);
    }

    @Test
    void reconstruct_rejects_future_cycle_end() {
        ledger.opening = Money.zero(CurrencyCode.USD);
        assertThatThrownBy(() -> service.reconstruct(context(),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), "csr", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void reconstruct_rejects_reversed_dates() {
        assertThatThrownBy(() -> service.reconstruct(context(),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), "csr", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstruct_rejects_currency_mismatch_between_ledger_and_context() {
        ledger.opening = Money.of(1000, CurrencyCode.EUR);
        assertThatThrownBy(() -> service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "csr", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void reconstruct_skips_postings_with_wrong_currency_and_warns() {
        ledger.opening = Money.of(100, CurrencyCode.USD);
        ledger.postings.add(new LedgerPosting("REF-EUR", LocalDate.of(2026, 4, 3),
                Money.of(50, CurrencyCode.EUR), "EUR wire", LineType.DEPOSIT));

        ReconstructionResult result = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "migration");

        assertThat(result.content().summary().closingBalance()).isEqualTo(Money.of(100, CurrencyCode.USD));
        assertThat(result.auditTrail().warnings()).anyMatch(s -> s.contains("Skipped posting"));
    }

    @Test
    void reconstruct_emits_warning_when_no_postings() {
        ledger.opening = Money.of(500, CurrencyCode.USD);
        ReconstructionResult r = service.reconstruct(context(),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "ops", "x");
        assertThat(r.auditTrail().warnings()).anyMatch(w -> w.contains("No postings"));
    }

    @Test
    void reconstruction_includes_holds_from_ledger() {
        ledger.opening = Money.zero(CurrencyCode.USD);
        ledger.holds.add(new HoldSummary("Check hold",
                Money.of(50, CurrencyCode.USD),
                LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 10)));
        ReconstructionResult r = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");
        assertThat(r.content().holds()).hasSize(1);
    }

    @Test
    void verify_against_archived_statement_returns_true_for_match() {
        ledger.opening = Money.of(1000, CurrencyCode.USD);
        ledger.postings.add(new LedgerPosting("REF-1", LocalDate.of(2026, 4, 3),
                Money.of(500, CurrencyCode.USD), "dep", LineType.DEPOSIT));

        ReconstructionResult a = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");
        ReconstructionResult b = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");

        assertThat(service.verifyAgainst(a, b.content())).isTrue();
    }

    @Test
    void verify_against_archived_statement_returns_false_for_divergence() {
        ledger.opening = Money.of(1000, CurrencyCode.USD);
        ledger.postings.add(new LedgerPosting("REF-1", LocalDate.of(2026, 4, 3),
                Money.of(500, CurrencyCode.USD), "dep", LineType.DEPOSIT));
        ReconstructionResult a = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");

        ledger.postings.add(new LedgerPosting("REF-2", LocalDate.of(2026, 4, 10),
                Money.of(100, CurrencyCode.USD), "fee", LineType.FEE));
        ReconstructionResult b = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");

        assertThat(service.verifyAgainst(a, b.content())).isFalse();
    }

    @Test
    void audit_trail_captures_reconstruction_id_and_run_time() {
        ledger.opening = Money.of(100, CurrencyCode.USD);
        ReconstructionResult r = service.reconstruct(context(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "ops", "x");
        AuditTrail a = r.auditTrail();
        assertThat(a.reconstructionId()).startsWith("RECON-");
        assertThat(a.runAt()).isEqualTo(clock.instant());
    }

    private static final class FakeLedger implements LedgerSource {
        Money opening = Money.zero(CurrencyCode.USD);
        final List<LedgerPosting> postings = new ArrayList<>();
        final List<HoldSummary> holds = new ArrayList<>();

        @Override
        public List<LedgerPosting> postingsFor(AccountNumber account, LocalDate startInclusive, LocalDate endInclusive) {
            return new ArrayList<>(postings);
        }

        @Override
        public Money balanceAsOf(AccountNumber account, LocalDate asOf) {
            return opening;
        }

        @Override
        public List<HoldSummary> holdsFor(AccountNumber account, LocalDate startInclusive, LocalDate endInclusive) {
            return new ArrayList<>(holds);
        }
    }
}
