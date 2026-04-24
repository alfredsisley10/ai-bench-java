package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InterchangeCalculatorTest {

    private InterchangeCalculator calc;

    @BeforeEach
    void setUp() {
        calc = new InterchangeCalculator();
    }

    @Test
    void visa_credit_present_default_rate_applied() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "9999", Money.of("100.00", CurrencyCode.USD));

        // DEFAULT = 155 bps + $0.10 flat = 1.55 + 0.10 = $1.65
        assertThat(fee).isEqualTo(Money.of("1.65", CurrencyCode.USD));
    }

    @Test
    void grocery_mcc_uses_lower_rate_on_visa() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "5411", Money.of("100.00", CurrencyCode.USD));

        assertThat(fee).isEqualTo(Money.of("1.10", CurrencyCode.USD));
    }

    @Test
    void regulated_debit_uses_durbin_cap() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.DEBIT_REGULATED,
                "5812", Money.of("100.00", CurrencyCode.USD));

        // 5 bps + $0.22 flat on $100 = $0.05 + $0.22 = $0.27
        assertThat(fee).isEqualTo(Money.of("0.27", CurrencyCode.USD));
    }

    @Test
    void exempt_debit_uses_higher_tiered_rate() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.DEBIT_EXEMPT,
                "5999", Money.of("100.00", CurrencyCode.USD));

        // 115 bps + $0.15 = $1.15 + $0.15 = $1.30
        assertThat(fee).isEqualTo(Money.of("1.30", CurrencyCode.USD));
    }

    @Test
    void amex_charges_higher_rate_than_visa_credit() {
        var amex = calc.compute(CardNetwork.AMEX,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "9999", Money.of("100.00", CurrencyCode.USD));
        var visa = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "9999", Money.of("100.00", CurrencyCode.USD));

        assertThat(amex.compareTo(visa)).isGreaterThan(0);
    }

    @Test
    void mc_cnp_uses_digital_bucket_when_mcc_not_categorized() {
        var fee = calc.compute(CardNetwork.MASTERCARD,
                InterchangeCalculator.TransactionType.CREDIT_CARD_NOT_PRESENT,
                "9999", Money.of("100.00", CurrencyCode.USD));

        // MC_CREDIT_CNP has only DEFAULT (185 bps + $0.10) so falls back.
        assertThat(fee).isEqualTo(Money.of("1.95", CurrencyCode.USD));
    }

    @Test
    void zero_amount_yields_zero_fee() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "5812", Money.zero(CurrencyCode.USD));

        assertThat(fee).isEqualTo(Money.zero(CurrencyCode.USD));
    }

    @Test
    void effective_rate_is_reported_in_percent() {
        var rate = calc.effectiveRatePercent(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "5411", Money.of("100.00", CurrencyCode.USD));

        // 1.10 / 100 * 100 = 1.1%
        assertThat(rate).isEqualByComparingTo(new BigDecimal("1.1000"));
    }

    @Test
    void rate_entry_lookup_returns_table_value() {
        var rate = calc.rateFor(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "5411");

        assertThat(rate.bps()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void resolve_category_maps_mcc_to_category_key() {
        assertThat(InterchangeCalculator.resolveCategory("5411",
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT))
                .isEqualTo("GROCERY");
        assertThat(InterchangeCalculator.resolveCategory("8398",
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT))
                .isEqualTo("CHARITY");
        assertThat(InterchangeCalculator.resolveCategory(null,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT))
                .isEqualTo("DEFAULT");
    }

    @Test
    void airline_mcc_routes_to_airline_category_on_visa() {
        var fee = calc.compute(CardNetwork.VISA,
                InterchangeCalculator.TransactionType.CREDIT_CARD_PRESENT,
                "3058", Money.of("200.00", CurrencyCode.USD));

        // 170 bps + 0.10 on $200 = $3.40 + $0.10 = $3.50
        assertThat(fee).isEqualTo(Money.of("3.50", CurrencyCode.USD));
    }
}
