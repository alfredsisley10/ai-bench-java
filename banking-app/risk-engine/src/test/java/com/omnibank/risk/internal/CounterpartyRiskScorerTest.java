package com.omnibank.risk.internal;

import com.omnibank.risk.internal.CounterpartyRiskScorer.CounterpartyProfile;
import com.omnibank.risk.internal.CounterpartyRiskScorer.CounterpartyScore;
import com.omnibank.risk.internal.CounterpartyRiskScorer.CounterpartyType;
import com.omnibank.risk.internal.CounterpartyRiskScorer.Tier;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CounterpartyRiskScorerTest {

    private RecordingEventBus events;
    private CounterpartyRiskScorer scorer;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        scorer = new CounterpartyRiskScorer(clock, events, CurrencyCode.USD);
    }

    @Test
    void pristine_profile_scores_as_institutional_tier() {
        CounterpartyProfile profile = new CounterpartyProfile(CustomerId.newId(),
                "Prime Corp", CounterpartyType.LARGE_CORPORATE, "AAA", 1,
                new BigDecimal("0.8"), new BigDecimal("12.0"),
                new BigDecimal("80000000"),
                Money.of("10000000", CurrencyCode.USD),
                Money.of("20000000", CurrencyCode.USD),
                new BigDecimal("0.30"),
                "US", false);

        CounterpartyScore s = scorer.score(profile);
        assertThat(s.tier()).isEqualTo(Tier.INSTITUTIONAL_AAA);
        assertThat(s.pd()).isLessThan(new BigDecimal("0.0020"));
        assertThat(s.expectedLoss().isPositive()).isTrue();
    }

    @Test
    void watchlist_is_assigned_for_grade_9_or_worse() {
        CounterpartyProfile profile = new CounterpartyProfile(CustomerId.newId(),
                "Distressed", CounterpartyType.MID_CORPORATE, "CCC", 9,
                new BigDecimal("9.0"), new BigDecimal("0.5"),
                BigDecimal.ZERO,
                Money.of("5000000", CurrencyCode.USD),
                Money.of("5000000", CurrencyCode.USD),
                new BigDecimal("0.75"),
                "US", false);

        CounterpartyScore s = scorer.score(profile);
        assertThat(s.tier()).isEqualTo(Tier.WATCHLIST);
    }

    @Test
    void collateral_reduces_lgd_towards_regulatory_floor() {
        CounterpartyProfile unsecured = new CounterpartyProfile(CustomerId.newId(),
                "A", CounterpartyType.LARGE_CORPORATE, "BBB", 5,
                new BigDecimal("3.0"), new BigDecimal("4.0"),
                BigDecimal.ZERO,
                Money.of("1000000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD),
                new BigDecimal("0.75"),
                "US", false);
        CounterpartyProfile secured = new CounterpartyProfile(unsecured.counterparty(),
                "A", CounterpartyType.LARGE_CORPORATE, "BBB", 5,
                new BigDecimal("3.0"), new BigDecimal("4.0"),
                new BigDecimal("900000"),  // 90% collateralised
                Money.of("1000000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD),
                new BigDecimal("0.75"),
                "US", false);

        BigDecimal lgdUnsecured = scorer.lossGivenDefault(unsecured);
        BigDecimal lgdSecured = scorer.lossGivenDefault(secured);

        assertThat(lgdSecured).isLessThan(lgdUnsecured);
    }

    @Test
    void ead_equals_drawn_plus_ccf_times_undrawn() {
        CounterpartyProfile profile = new CounterpartyProfile(CustomerId.newId(),
                "X", CounterpartyType.MID_CORPORATE, "BBB", 5,
                new BigDecimal("2.0"), new BigDecimal("3.0"),
                BigDecimal.ZERO,
                Money.of("300000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);

        Money ead = scorer.exposureAtDefault(profile);
        assertThat(ead).isEqualTo(Money.of("650000", CurrencyCode.USD));
    }

    @Test
    void high_leverage_lifts_probability_of_default() {
        CounterpartyProfile lowLev = new CounterpartyProfile(CustomerId.newId(),
                "Low", CounterpartyType.LARGE_CORPORATE, "A", 4,
                new BigDecimal("1.0"), new BigDecimal("5.0"),
                BigDecimal.ZERO,
                Money.of("500000", CurrencyCode.USD),
                Money.of("500000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);
        CounterpartyProfile highLev = new CounterpartyProfile(CustomerId.newId(),
                "High", CounterpartyType.LARGE_CORPORATE, "A", 4,
                new BigDecimal("9.0"), new BigDecimal("5.0"),
                BigDecimal.ZERO,
                Money.of("500000", CurrencyCode.USD),
                Money.of("500000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);

        BigDecimal lowPd = scorer.probabilityOfDefault(lowLev);
        BigDecimal highPd = scorer.probabilityOfDefault(highLev);
        assertThat(highPd).isGreaterThan(lowPd);
    }

    @Test
    void low_interest_coverage_lifts_probability_of_default() {
        CounterpartyProfile healthy = new CounterpartyProfile(CustomerId.newId(),
                "H", CounterpartyType.LARGE_CORPORATE, "A", 4,
                new BigDecimal("3.0"), new BigDecimal("10.0"),
                BigDecimal.ZERO,
                Money.of("500000", CurrencyCode.USD),
                Money.of("500000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);
        CounterpartyProfile strained = new CounterpartyProfile(CustomerId.newId(),
                "S", CounterpartyType.LARGE_CORPORATE, "A", 4,
                new BigDecimal("3.0"), new BigDecimal("0.8"),
                BigDecimal.ZERO,
                Money.of("500000", CurrencyCode.USD),
                Money.of("500000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);

        assertThat(scorer.probabilityOfDefault(strained))
                .isGreaterThan(scorer.probabilityOfDefault(healthy));
    }

    @Test
    void guaranteed_exposure_gets_lower_lgd_than_unguaranteed() {
        CounterpartyProfile unguaranteed = new CounterpartyProfile(CustomerId.newId(),
                "U", CounterpartyType.LARGE_CORPORATE, "BBB", 5,
                new BigDecimal("3.0"), new BigDecimal("3.0"),
                new BigDecimal("200000"),
                Money.of("1000000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);
        CounterpartyProfile guaranteed = new CounterpartyProfile(unguaranteed.counterparty(),
                "G", CounterpartyType.LARGE_CORPORATE, "BBB", 5,
                new BigDecimal("3.0"), new BigDecimal("3.0"),
                new BigDecimal("200000"),
                Money.of("1000000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", true);

        assertThat(scorer.lossGivenDefault(guaranteed))
                .isLessThanOrEqualTo(scorer.lossGivenDefault(unguaranteed));
    }

    @Test
    void scoring_publishes_scored_event_and_caches_result() {
        CustomerId c = CustomerId.newId();
        CounterpartyProfile profile = new CounterpartyProfile(c,
                "X", CounterpartyType.MID_CORPORATE, "BBB", 5,
                new BigDecimal("2.5"), new BigDecimal("3.5"),
                BigDecimal.ZERO,
                Money.of("100000", CurrencyCode.USD),
                Money.of("100000", CurrencyCode.USD),
                new BigDecimal("0.75"),
                "US", false);

        scorer.score(profile);
        assertThat(events.events)
                .anyMatch(e -> e instanceof CounterpartyRiskScorer.CounterpartyScoredEvent);
        assertThat(scorer.cachedScore(c)).isPresent();
    }

    @Test
    void currency_mismatch_is_rejected() {
        CounterpartyProfile wrong = new CounterpartyProfile(CustomerId.newId(),
                "X", CounterpartyType.MID_CORPORATE, "BBB", 5,
                new BigDecimal("2.5"), new BigDecimal("3.5"),
                BigDecimal.ZERO,
                Money.of("100000", CurrencyCode.EUR),
                Money.of("100000", CurrencyCode.EUR),
                new BigDecimal("0.75"),
                "US", false);
        assertThatThrownBy(() -> scorer.score(wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalid_internal_grade_is_rejected() {
        assertThatThrownBy(() -> new CounterpartyProfile(CustomerId.newId(),
                "X", CounterpartyType.MID_CORPORATE, "BBB", 0,
                new BigDecimal("2.5"), new BigDecimal("3.5"),
                BigDecimal.ZERO,
                Money.of("100000", CurrencyCode.USD),
                Money.of("100000", CurrencyCode.USD),
                new BigDecimal("0.75"),
                "US", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tier_assignment_is_stricter_for_higher_pd() {
        CounterpartyProfile a = new CounterpartyProfile(CustomerId.newId(),
                "A", CounterpartyType.LARGE_CORPORATE, "A", 3,
                new BigDecimal("2.0"), new BigDecimal("5.0"),
                BigDecimal.ZERO,
                Money.of("100000", CurrencyCode.USD),
                Money.of("100000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);
        CounterpartyProfile b = new CounterpartyProfile(CustomerId.newId(),
                "B", CounterpartyType.LARGE_CORPORATE, "B", 7,
                new BigDecimal("5.0"), new BigDecimal("1.2"),
                BigDecimal.ZERO,
                Money.of("100000", CurrencyCode.USD),
                Money.of("100000", CurrencyCode.USD),
                new BigDecimal("0.50"),
                "US", false);

        Tier ta = scorer.score(a).tier();
        Tier tb = scorer.score(b).tier();
        assertThat(ta.ordinal()).isLessThan(tb.ordinal());
    }
}
