package com.omnibank.risk.internal;

import com.omnibank.risk.internal.ExposureLimitEnforcer.ExposureChange;
import com.omnibank.risk.internal.ExposureLimitEnforcer.LimitBreach;
import com.omnibank.risk.internal.ExposureLimitEnforcer.LimitPolicy;
import com.omnibank.risk.internal.ExposureLimitEnforcer.LimitType;
import com.omnibank.risk.internal.ExposureLimitEnforcer.Severity;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExposureLimitEnforcerTest {

    private static final Money TIER1 = Money.of("1000000000", CurrencyCode.USD);

    private RecordingEventBus events;
    private ExposureLimitEnforcer enforcer;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        enforcer = new ExposureLimitEnforcer(clock, events, CurrencyCode.USD, TIER1);
    }

    @Test
    void small_change_does_not_breach_any_limit() {
        CustomerId c = CustomerId.newId();
        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                c, "ACME Corp", "541", "US",
                Money.of("1000000", CurrencyCode.USD)));

        assertThat(breaches).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void large_exposure_threshold_triggers_info_breach_and_register_entry() {
        CustomerId c = CustomerId.newId();
        // 12% of Tier 1 = $120m
        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                c, "BigCo", "541", "US",
                Money.of("120000000", CurrencyCode.USD)));

        assertThat(breaches).anyMatch(b -> b.limitType() == LimitType.LARGE_EXPOSURE);
        assertThat(enforcer.largeExposures()).containsKey(c);
    }

    @Test
    void single_obligor_hard_cap_breach_at_25pct() {
        CustomerId c = CustomerId.newId();
        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                c, "WhaleCo", "541", "US",
                Money.of("300000000", CurrencyCode.USD)));   // 30% of Tier 1

        assertThat(breaches).anyMatch(b -> b.limitType() == LimitType.SINGLE_OBLIGOR
                && b.severity() == Severity.BREACH);
        assertThat(events.events)
                .anyMatch(e -> e instanceof ExposureLimitEnforcer.ExposureLimitBreachEvent);
    }

    @Test
    void sector_policy_breach_fires_when_limit_exceeded() {
        enforcer.setPolicy(new LimitPolicy(LimitType.SECTOR, "541",
                Money.of("50000000", CurrencyCode.USD)));

        // Use distinct obligors so we don't trip the obligor cap instead.
        enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                CustomerId.newId(), "A", "541", "US",
                Money.of("30000000", CurrencyCode.USD)));
        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                CustomerId.newId(), "B", "541", "US",
                Money.of("30000000", CurrencyCode.USD)));

        assertThat(breaches).anyMatch(b -> b.limitType() == LimitType.SECTOR);
    }

    @Test
    void country_policy_breach_respects_limit() {
        enforcer.setPolicy(new LimitPolicy(LimitType.COUNTRY, "BR",
                Money.of("10000000", CurrencyCode.USD)));

        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                CustomerId.newId(), "SP Brasil", "541", "br",    // lowercase exercises normalisation
                Money.of("12000000", CurrencyCode.USD)));

        assertThat(breaches).anyMatch(b -> b.limitType() == LimitType.COUNTRY);
    }

    @Test
    void paydown_below_threshold_removes_large_exposure_entry() {
        CustomerId c = CustomerId.newId();
        enforcer.apply(new ExposureChange("1", c, "Big", "541", "US",
                Money.of("120000000", CurrencyCode.USD)));
        assertThat(enforcer.largeExposures()).containsKey(c);

        enforcer.apply(new ExposureChange("2", c, "Big", "541", "US",
                Money.of("-80000000", CurrencyCode.USD)));

        // 40m = 4% of tier 1 → below 10% threshold
        assertThat(enforcer.largeExposures()).doesNotContainKey(c);
    }

    @Test
    void currency_mismatch_is_rejected() {
        assertThatThrownBy(() -> enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                CustomerId.newId(), "A", "541", "US",
                Money.of("1000", CurrencyCode.EUR))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregate_large_exposure_pct_reflects_register() {
        CustomerId a = CustomerId.newId();
        CustomerId b = CustomerId.newId();
        enforcer.apply(new ExposureChange("1", a, "A", "541", "US",
                Money.of("110000000", CurrencyCode.USD)));
        enforcer.apply(new ExposureChange("2", b, "B", "541", "US",
                Money.of("150000000", CurrencyCode.USD)));

        BigDecimal agg = enforcer.aggregateLargeExposurePct();
        // 260m / 1bn = 26%
        assertThat(agg).isEqualByComparingTo(new BigDecimal("26.00"));
    }

    @Test
    void updating_tier1_changes_breach_calculations() {
        enforcer.updateTier1Capital(Money.of("100000000", CurrencyCode.USD));
        CustomerId c = CustomerId.newId();
        // Only 30m, but Tier 1 is now 100m → 30% of capital, breaches hard cap.
        var breaches = enforcer.apply(new ExposureChange(UUID.randomUUID().toString(),
                c, "Medium", "541", "US",
                Money.of("30000000", CurrencyCode.USD)));

        assertThat(breaches).anyMatch(b -> b.limitType() == LimitType.SINGLE_OBLIGOR
                && b.severity() == Severity.BREACH);
    }

    @Test
    void apply_returns_list_even_without_breaches() {
        List<LimitBreach> breaches = enforcer.apply(new ExposureChange(
                UUID.randomUUID().toString(), CustomerId.newId(),
                "A", null, null,
                Money.of("100", CurrencyCode.USD)));
        assertThat(breaches).isNotNull();
    }
}
