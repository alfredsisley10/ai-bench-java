package com.omnibank.fraud.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityCheckEngineTest {

    private static final AccountNumber ACCT = AccountNumber.of("OB-C-ABCD1234");
    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-04-19T12:00:00Z");

    private Clock clock;
    private VelocityCheckEngine engine;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(T0, ZoneId.of("UTC"));
        engine = new VelocityCheckEngine(clock);
    }

    @Test
    void single_small_transaction_is_clear() {
        var event = txn(Money.of("50.00", CurrencyCode.USD), T0.minusSeconds(10));

        var result = engine.recordAndCheck(event);

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Clear.class);
        var snapshots = ((VelocityCheckEngine.VelocityResult.Clear) result).snapshots();
        assertThat(snapshots.get(VelocityCheckEngine.TimeWindow.ONE_MINUTE).transactionCount())
                .isEqualTo(1);
    }

    @Test
    void four_transactions_within_a_minute_breach_count_threshold() {
        for (int i = 0; i < 3; i++) {
            engine.record_(txn(Money.of("10.00", CurrencyCode.USD), T0.minusSeconds(i + 1)));
        }
        // 4th txn within the minute pushes count from 3 → 4 (limit is 3).
        var result = engine.recordAndCheck(
                txn(Money.of("10.00", CurrencyCode.USD), T0.minusSeconds(1)));

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Breach.class);
        var breach = (VelocityCheckEngine.VelocityResult.Breach) result;
        assertThat(breach.violations()).anyMatch(v -> v.ruleId().equals("VEL-ACC-1M-CNT"));
    }

    @Test
    void large_amount_in_one_minute_breaches_amount_threshold() {
        // Single $2,500 txn is over the $2,000/min cap.
        var result = engine.recordAndCheck(
                txn(Money.of("2500.00", CurrencyCode.USD), T0.minusSeconds(5)));

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Breach.class);
        var breach = (VelocityCheckEngine.VelocityResult.Breach) result;
        assertThat(breach.violations()).anyMatch(v -> v.ruleId().equals("VEL-ACC-1M-AMT"));
    }

    @Test
    void events_outside_window_are_excluded_from_count() {
        // 5 txns, but all are 2+ minutes old — outside the 1-minute window.
        for (int i = 0; i < 5; i++) {
            engine.record_(txn(Money.of("100.00", CurrencyCode.USD),
                    T0.minus(java.time.Duration.ofMinutes(2 + i))));
        }
        var result = engine.checkAccount(ACCT);

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Clear.class);
        var snapshots = ((VelocityCheckEngine.VelocityResult.Clear) result).snapshots();
        assertThat(snapshots.get(VelocityCheckEngine.TimeWindow.ONE_MINUTE).transactionCount())
                .isZero();
        // But the 1-hour window should see all 5.
        assertThat(snapshots.get(VelocityCheckEngine.TimeWindow.ONE_HOUR).transactionCount())
                .isEqualTo(5);
    }

    @Test
    void per_customer_threshold_aggregates_across_accounts() {
        var acct2 = AccountNumber.of("OB-C-WXYZ5678");
        // 26 small txns across 2 accounts within an hour breaches VEL-CUS-1H-CNT (>25).
        for (int i = 0; i < 13; i++) {
            engine.record_(new VelocityCheckEngine.TransactionEvent(
                    ACCT, CUSTOMER, Money.of("5.00", CurrencyCode.USD),
                    T0.minus(java.time.Duration.ofMinutes(i + 1)), "POS"));
            engine.record_(new VelocityCheckEngine.TransactionEvent(
                    acct2, CUSTOMER, Money.of("5.00", CurrencyCode.USD),
                    T0.minus(java.time.Duration.ofMinutes(i + 1)), "POS"));
        }
        var result = engine.checkCustomer(CUSTOMER, ACCT);

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Breach.class);
        var breach = (VelocityCheckEngine.VelocityResult.Breach) result;
        assertThat(breach.violations()).anyMatch(v -> v.ruleId().equals("VEL-CUS-1H-CNT"));
    }

    @Test
    void evict_stale_events_purges_events_older_than_8_days() {
        engine.record_(txn(Money.of("1.00", CurrencyCode.USD),
                T0.minus(java.time.Duration.ofDays(10))));
        engine.record_(txn(Money.of("1.00", CurrencyCode.USD),
                T0.minus(java.time.Duration.ofDays(1))));

        engine.evictStaleEvents();

        var stats = engine.getStats();
        assertThat(stats.totalAccountEvents()).isEqualTo(1);
    }

    @Test
    void unknown_account_returns_clear_with_zero_counts() {
        var result = engine.checkAccount(AccountNumber.of("OB-C-NEVRSEEN"));

        assertThat(result).isInstanceOf(VelocityCheckEngine.VelocityResult.Clear.class);
        var snapshots = ((VelocityCheckEngine.VelocityResult.Clear) result).snapshots();
        snapshots.values().forEach(s -> assertThat(s.transactionCount()).isZero());
    }

    @Test
    void stats_increase_with_records_and_drop_to_zero_when_evicted() {
        engine.record_(txn(Money.of("1.00", CurrencyCode.USD), T0.minusSeconds(1)));
        engine.record_(txn(Money.of("1.00", CurrencyCode.USD),
                T0.minus(java.time.Duration.ofDays(20))));
        assertThat(engine.getStats().trackedAccounts()).isEqualTo(1);
        assertThat(engine.getStats().totalAccountEvents()).isEqualTo(2);

        engine.evictStaleEvents();
        var after = engine.getStats();
        assertThat(after.totalAccountEvents()).isEqualTo(1);
    }

    private VelocityCheckEngine.TransactionEvent txn(Money amount, Instant when) {
        return new VelocityCheckEngine.TransactionEvent(ACCT, CUSTOMER, amount, when, "POS");
    }
}
