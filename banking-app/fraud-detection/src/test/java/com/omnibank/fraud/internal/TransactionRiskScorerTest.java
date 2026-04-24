package com.omnibank.fraud.internal;

import com.omnibank.fraud.api.FraudDecision;
import com.omnibank.fraud.internal.TransactionRiskScorer.TransactionContext;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRiskScorerTest {

    private static final AccountNumber ACCT = AccountNumber.of("OB-C-ABCD1234");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private TransactionRiskScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new TransactionRiskScorer(Clock.fixed(Instant.parse("2026-04-16T15:00:00Z"), ET));
    }

    @Test
    void low_risk_domestic_transaction_passes() {
        var ctx = baseline()
                .amount(Money.of("50.00", CurrencyCode.USD))
                .merchantMcc("5812") // restaurant
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-abc")
                .deviceTrusted(true)
                .deviceTrustScore(95)
                .channel("CARD_PRESENT")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(3)
                .averageTransactionAmount(Money.of("60.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.verdict()).isEqualTo(FraudDecision.Verdict.PASS);
        assertThat(decision.score()).isLessThan(400);
    }

    @Test
    void high_risk_mcc_triggers_elevated_score() {
        var ctx = baseline()
                .amount(Money.of("200.00", CurrencyCode.USD))
                .merchantMcc("7995") // gambling
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-known")
                .deviceTrusted(true)
                .deviceTrustScore(80)
                .channel("ONLINE")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(2)
                .averageTransactionAmount(Money.of("100.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("High-risk MCC"));
    }

    @Test
    void high_risk_country_triggers_block_or_review() {
        var ctx = baseline()
                .amount(Money.of("15000.00", CurrencyCode.USD))
                .merchantMcc("5999")
                .merchantCountry("NG")
                .customerHomeCountry("US")
                .deviceFingerprint(null)
                .deviceTrusted(false)
                .deviceTrustScore(10)
                .channel("ONLINE")
                .recentTxnCount1Hr(8)
                .recentTxnCount24Hr(20)
                .averageTransactionAmount(Money.of("50.00", CurrencyCode.USD))
                .transactionTime(midnight())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.verdict()).isIn(FraudDecision.Verdict.BLOCK, FraudDecision.Verdict.REVIEW);
        assertThat(decision.signals()).anyMatch(s -> s.contains("High-risk country"));
    }

    @Test
    void extreme_velocity_alone_produces_block() {
        var ctx = baseline()
                .amount(Money.of("75.00", CurrencyCode.USD))
                .merchantMcc("5812")
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-trusted")
                .deviceTrusted(true)
                .deviceTrustScore(85)
                .channel("MOBILE")
                .recentTxnCount1Hr(20)
                .recentTxnCount24Hr(40)
                .averageTransactionAmount(Money.of("80.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("Extreme velocity"));
    }

    @Test
    void amount_above_ctr_threshold_emits_ctr_signal() {
        var ctx = baseline()
                .amount(Money.of("12000.00", CurrencyCode.USD))
                .merchantMcc("5411")
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-ok")
                .deviceTrusted(true)
                .deviceTrustScore(85)
                .channel("CARD_PRESENT")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(2)
                .averageTransactionAmount(Money.of("150.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("CTR threshold"));
    }

    @Test
    void unrecognized_device_emits_device_signal() {
        var ctx = baseline()
                .amount(Money.of("100.00", CurrencyCode.USD))
                .merchantMcc("5812")
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-new")
                .deviceTrusted(false)
                .deviceTrustScore(25)
                .channel("ONLINE")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(1)
                .averageTransactionAmount(Money.of("150.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("Unrecognized device"));
    }

    @Test
    void unusual_hour_adds_time_of_day_signal() {
        var ctx = baseline()
                .amount(Money.of("100.00", CurrencyCode.USD))
                .merchantMcc("5812")
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-known")
                .deviceTrusted(true)
                .deviceTrustScore(90)
                .channel("CARD_PRESENT")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(2)
                .averageTransactionAmount(Money.of("95.00", CurrencyCode.USD))
                .transactionTime(ZonedDateTime.of(2026, 4, 16, 3, 0, 0, 0, ET).toInstant())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("Unusual time of day"));
    }

    @Test
    void cross_border_adds_cross_border_signal() {
        var ctx = baseline()
                .amount(Money.of("100.00", CurrencyCode.USD))
                .merchantMcc("5812")
                .merchantCountry("CA")
                .customerHomeCountry("US")
                .deviceFingerprint("device-known")
                .deviceTrusted(true)
                .deviceTrustScore(80)
                .channel("CARD_PRESENT")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(2)
                .averageTransactionAmount(Money.of("95.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("Cross-border"));
    }

    @Test
    void amount_ten_times_average_triggers_top_score() {
        var ctx = baseline()
                .amount(Money.of("1500.00", CurrencyCode.USD))
                .merchantMcc("5812")
                .merchantCountry("US")
                .customerHomeCountry("US")
                .deviceFingerprint("device-known")
                .deviceTrusted(true)
                .deviceTrustScore(80)
                .channel("ONLINE")
                .recentTxnCount1Hr(1)
                .recentTxnCount24Hr(2)
                .averageTransactionAmount(Money.of("100.00", CurrencyCode.USD))
                .transactionTime(midday())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.signals()).anyMatch(s -> s.contains("10x+"));
    }

    @Test
    void composite_score_capped_at_thousand() {
        var ctx = baseline()
                .amount(Money.of("100000.00", CurrencyCode.USD))
                .merchantMcc("7995")
                .merchantCountry("NG")
                .customerHomeCountry("US")
                .deviceFingerprint(null)
                .deviceTrusted(false)
                .deviceTrustScore(1)
                .channel("ONLINE")
                .recentTxnCount1Hr(50)
                .recentTxnCount24Hr(100)
                .averageTransactionAmount(Money.of("20.00", CurrencyCode.USD))
                .transactionTime(ZonedDateTime.of(2026, 4, 16, 2, 30, 0, 0, ET).toInstant())
                .build();

        var decision = scorer.score(ctx);

        assertThat(decision.score()).isLessThanOrEqualTo(1000);
        assertThat(decision.verdict()).isEqualTo(FraudDecision.Verdict.BLOCK);
    }

    private Instant midday() {
        return ZonedDateTime.of(2026, 4, 16, 13, 0, 0, 0, ET).toInstant();
    }

    private Instant midnight() {
        return ZonedDateTime.of(2026, 4, 16, 2, 0, 0, 0, ET).toInstant();
    }

    private static TxnContextBuilder baseline() {
        return new TxnContextBuilder();
    }

    private static final class TxnContextBuilder {
        AccountNumber account = ACCT;
        Money amount;
        String merchantMcc;
        String merchantCountry;
        String customerHomeCountry;
        String deviceFingerprint;
        boolean deviceTrusted;
        int deviceTrustScore;
        String channel;
        Instant transactionTime;
        int recentTxnCount1Hr;
        int recentTxnCount24Hr;
        Money averageTransactionAmount;

        TxnContextBuilder amount(Money v) { this.amount = v; return this; }
        TxnContextBuilder merchantMcc(String v) { this.merchantMcc = v; return this; }
        TxnContextBuilder merchantCountry(String v) { this.merchantCountry = v; return this; }
        TxnContextBuilder customerHomeCountry(String v) { this.customerHomeCountry = v; return this; }
        TxnContextBuilder deviceFingerprint(String v) { this.deviceFingerprint = v; return this; }
        TxnContextBuilder deviceTrusted(boolean v) { this.deviceTrusted = v; return this; }
        TxnContextBuilder deviceTrustScore(int v) { this.deviceTrustScore = v; return this; }
        TxnContextBuilder channel(String v) { this.channel = v; return this; }
        TxnContextBuilder transactionTime(Instant v) { this.transactionTime = v; return this; }
        TxnContextBuilder recentTxnCount1Hr(int v) { this.recentTxnCount1Hr = v; return this; }
        TxnContextBuilder recentTxnCount24Hr(int v) { this.recentTxnCount24Hr = v; return this; }
        TxnContextBuilder averageTransactionAmount(Money v) { this.averageTransactionAmount = v; return this; }

        TransactionContext build() {
            return new TransactionContext(account, amount, merchantMcc, merchantCountry,
                    customerHomeCountry, deviceFingerprint, deviceTrusted, deviceTrustScore,
                    channel, transactionTime, recentTxnCount1Hr, recentTxnCount24Hr,
                    averageTransactionAmount);
        }
    }
}
