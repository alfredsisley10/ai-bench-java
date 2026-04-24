package com.omnibank.productvariants.teenchecking;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pricing and interest accrual for the Teen Checking product. Uses simple
 * daily accrual at {@link #baseRate()} unless the balance enters a
 * tiered bucket that triggers a promotional or relationship bonus.
 *
 * <p>All arithmetic uses banker's rounding. Negative balances accrue
 * at the default APR (not APY) — checked downstream by the overdraft
 * engine.
 */
public final class TeenCheckingPricingEngine {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_EVEN);
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    public record Tier(BigDecimal lowerInclusive, BigDecimal upperExclusive, BigDecimal bonusBps) {
        public Tier {
            Objects.requireNonNull(lowerInclusive, "lowerInclusive");
            Objects.requireNonNull(bonusBps, "bonusBps");
        }
    }

    public record AccrualResult(
            BigDecimal dailyAmount,
            BigDecimal effectiveRate,
            BigDecimal runningTotal,
            String tierName,
            LocalDate accrualDate
    ) {}

    private final BigDecimal baseRate;
    private final List<Tier> tiers;

    public TeenCheckingPricingEngine() {
        this(new BigDecimal("0.0010"));
    }

    public TeenCheckingPricingEngine(BigDecimal baseRate) {
        this.baseRate = Objects.requireNonNull(baseRate, "baseRate");
        this.tiers = defaultTiers();
    }

    public BigDecimal baseRate() {
        return baseRate;
    }

    public BigDecimal effectiveRate(BigDecimal balance) {
        Tier applicable = selectTier(balance);
        BigDecimal bonus = applicable != null
                ? applicable.bonusBps().divide(BigDecimal.valueOf(10000), MC)
                : BigDecimal.ZERO;
        return baseRate.add(bonus, MC);
    }

    public AccrualResult accrueDaily(BigDecimal balance, LocalDate asOf,
                                     BigDecimal priorTotal) {
        BigDecimal rate = effectiveRate(balance);
        BigDecimal daily = balance
                .multiply(rate, MC)
                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_EVEN);
        BigDecimal running = priorTotal.add(daily);
        Tier applicable = selectTier(balance);
        String tierName = applicable == null ? "STANDARD"
                : "TIER_" + applicable.lowerInclusive().toPlainString();
        return new AccrualResult(daily, rate, running, tierName, asOf);
    }

    public BigDecimal annualPercentageYield(BigDecimal balance) {
        // APY = (1 + r/365)^365 - 1
        BigDecimal rate = effectiveRate(balance);
        BigDecimal perDay = rate.divide(DAYS_PER_YEAR, MC);
        BigDecimal one = BigDecimal.ONE;
        BigDecimal base = one.add(perDay, MC);
        BigDecimal compounded = BigDecimal.ONE;
        // 365 multiplications — accurate enough for disclosure.
        for (int i = 0; i < 365; i++) {
            compounded = compounded.multiply(base, MC);
        }
        return compounded.subtract(one, MC).setScale(6, RoundingMode.HALF_EVEN);
    }

    public BigDecimal projectInterest(BigDecimal principal, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days <= 0) return BigDecimal.ZERO;
        BigDecimal rate = effectiveRate(principal);
        return principal
                .multiply(rate, MC)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_EVEN);
    }

    public Tier selectTier(BigDecimal balance) {
        Tier selected = null;
        for (Tier t : tiers) {
            if (balance.compareTo(t.lowerInclusive()) >= 0
                    && (t.upperExclusive() == null
                            || balance.compareTo(t.upperExclusive()) < 0)) {
                selected = t;
            }
        }
        return selected;
    }

    private List<Tier> defaultTiers() {
        List<Tier> list = new ArrayList<>();
        list.add(new Tier(BigDecimal.ZERO, new BigDecimal("10000"),
                BigDecimal.ZERO));
        list.add(new Tier(new BigDecimal("10000"), new BigDecimal("50000"),
                new BigDecimal("10")));
        list.add(new Tier(new BigDecimal("50000"), new BigDecimal("250000"),
                new BigDecimal("20")));
        list.add(new Tier(new BigDecimal("250000"), null,
                new BigDecimal("35")));
        return list;
    }
}
