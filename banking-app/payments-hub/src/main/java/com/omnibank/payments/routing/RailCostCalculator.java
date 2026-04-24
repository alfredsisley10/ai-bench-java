package com.omnibank.payments.routing;

import com.omnibank.payments.api.PaymentRail;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates per-payment costs across all supported rails.
 *
 * <p>Cost components vary by rail:
 * <ul>
 *   <li><b>ACH</b>: NACHA network fee + originating bank fee + receiving bank fee (volume tiered)</li>
 *   <li><b>Wire</b>: Fedwire fee + originating bank fee + possible correspondent bank charges</li>
 *   <li><b>RTP</b>: TCH network fee (per-transaction, flat + basis points)</li>
 *   <li><b>FedNow</b>: Federal Reserve network fee (flat, per-transaction)</li>
 *   <li><b>Book</b>: Zero cost (internal transfer)</li>
 * </ul>
 *
 * <p>Volume tier pricing applies to ACH and Wire, where the monthly transaction
 * count determines the per-transaction rate. Tiers are evaluated at the beginning
 * of each billing cycle.
 */
public class RailCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(RailCostCalculator.class);

    /**
     * A single cost component that contributes to the total payment cost.
     */
    public record CostComponent(
            String name,
            CostType type,
            BigDecimal value,
            String description
    ) {
        public CostComponent {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }

        public Money computeAmount(Money paymentAmount) {
            return switch (type) {
                case FLAT_FEE -> Money.of(value, CurrencyCode.USD);
                case BASIS_POINTS -> {
                    var bps = value.divide(BigDecimal.valueOf(10000), 8, RoundingMode.HALF_EVEN);
                    yield Money.of(paymentAmount.amount().multiply(bps).setScale(2, RoundingMode.HALF_EVEN),
                            CurrencyCode.USD);
                }
                case PERCENTAGE -> {
                    var pct = value.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
                    yield Money.of(paymentAmount.amount().multiply(pct).setScale(2, RoundingMode.HALF_EVEN),
                            CurrencyCode.USD);
                }
            };
        }
    }

    public enum CostType {
        FLAT_FEE,
        BASIS_POINTS,
        PERCENTAGE
    }

    /**
     * Volume tier: monthly transaction count ranges that determine per-transaction pricing.
     */
    public record VolumeTier(
            int minTransactions,
            int maxTransactions,
            List<CostComponent> components
    ) {
        public VolumeTier {
            Objects.requireNonNull(components, "components");
            if (minTransactions < 0 || (maxTransactions != Integer.MAX_VALUE && maxTransactions < minTransactions)) {
                throw new IllegalArgumentException("Invalid tier range: %d-%d".formatted(minTransactions, maxTransactions));
            }
        }

        public boolean appliesTo(int monthlyVolume) {
            return monthlyVolume >= minTransactions && monthlyVolume <= maxTransactions;
        }
    }

    public record CostBreakdown(
            PaymentRail rail,
            Money paymentAmount,
            List<CostComponent> appliedComponents,
            Money totalCost
    ) {}

    /**
     * Correspondent bank surcharge for wire transfers routed through intermediary banks.
     */
    public record CorrespondentCharge(
            String correspondentName,
            String correspondentBic,
            Money fixedCharge,
            BigDecimal basisPointCharge
    ) {
        public CorrespondentCharge {
            Objects.requireNonNull(correspondentName, "correspondentName");
        }
    }

    private final Map<PaymentRail, List<VolumeTier>> volumeTiers = new ConcurrentHashMap<>();
    private final Map<PaymentRail, List<CostComponent>> baseCosts = new ConcurrentHashMap<>();
    private final Map<String, CorrespondentCharge> correspondentCharges = new ConcurrentHashMap<>();
    private final Map<PaymentRail, Integer> currentMonthlyVolumes = new ConcurrentHashMap<>();

    public RailCostCalculator() {
        initializeDefaultCosts();
    }

    /**
     * Calculates the total cost for sending a payment on the specified rail.
     */
    public Money calculateCost(PaymentRail rail, Money paymentAmount) {
        var breakdown = calculateCostBreakdown(rail, paymentAmount);
        return breakdown.totalCost();
    }

    /**
     * Returns a detailed cost breakdown for the payment on the specified rail.
     */
    public CostBreakdown calculateCostBreakdown(PaymentRail rail, Money paymentAmount) {
        if (rail == PaymentRail.BOOK) {
            return new CostBreakdown(rail, paymentAmount, List.of(), Money.zero(CurrencyCode.USD));
        }

        var appliedComponents = new java.util.ArrayList<CostComponent>();

        // Base costs (always applied)
        var base = baseCosts.getOrDefault(rail, List.of());
        appliedComponents.addAll(base);

        // Volume-tiered costs (replace base if applicable)
        var tiers = volumeTiers.get(rail);
        if (tiers != null) {
            int monthlyVol = currentMonthlyVolumes.getOrDefault(rail, 0);
            tiers.stream()
                    .filter(t -> t.appliesTo(monthlyVol))
                    .findFirst()
                    .ifPresent(tier -> appliedComponents.addAll(tier.components()));
        }

        // Sum all cost components
        var totalCost = Money.zero(CurrencyCode.USD);
        for (var component : appliedComponents) {
            totalCost = totalCost.plus(component.computeAmount(paymentAmount));
        }

        log.debug("Cost calculated: rail={}, amount={}, components={}, total={}",
                rail, paymentAmount, appliedComponents.size(), totalCost);

        return new CostBreakdown(rail, paymentAmount, List.copyOf(appliedComponents), totalCost);
    }

    /**
     * Calculates the total cost including correspondent bank charges for a wire.
     */
    public CostBreakdown calculateWireCostWithCorrespondent(
            Money paymentAmount, String correspondentBic) {

        var breakdown = calculateCostBreakdown(PaymentRail.WIRE, paymentAmount);
        var charge = correspondentCharges.get(correspondentBic);

        if (charge == null) {
            return breakdown;
        }

        var augmentedComponents = new java.util.ArrayList<>(breakdown.appliedComponents());
        var correspondentCost = Money.zero(CurrencyCode.USD);

        if (charge.fixedCharge() != null) {
            augmentedComponents.add(new CostComponent(
                    "Correspondent fixed charge", CostType.FLAT_FEE,
                    charge.fixedCharge().amount(), "Charge from " + charge.correspondentName()));
            correspondentCost = correspondentCost.plus(charge.fixedCharge());
        }

        if (charge.basisPointCharge() != null && charge.basisPointCharge().compareTo(BigDecimal.ZERO) > 0) {
            augmentedComponents.add(new CostComponent(
                    "Correspondent variable charge", CostType.BASIS_POINTS,
                    charge.basisPointCharge(), "BPS charge from " + charge.correspondentName()));
            var bpsAmount = paymentAmount.amount()
                    .multiply(charge.basisPointCharge())
                    .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_EVEN);
            correspondentCost = correspondentCost.plus(Money.of(bpsAmount, CurrencyCode.USD));
        }

        return new CostBreakdown(
                PaymentRail.WIRE, paymentAmount,
                List.copyOf(augmentedComponents),
                breakdown.totalCost().plus(correspondentCost));
    }

    /**
     * Compares costs across all eligible rails for the given payment amount.
     */
    public Map<PaymentRail, Money> compareCostsAcrossRails(Money paymentAmount) {
        var comparison = new TreeMap<PaymentRail, Money>();
        for (var rail : PaymentRail.values()) {
            try {
                comparison.put(rail, calculateCost(rail, paymentAmount));
            } catch (Exception e) {
                log.debug("Cannot calculate cost for rail {}: {}", rail, e.getMessage());
            }
        }
        return comparison;
    }

    /**
     * Updates the monthly volume counter for a rail (affects tiered pricing).
     */
    public void incrementMonthlyVolume(PaymentRail rail) {
        currentMonthlyVolumes.merge(rail, 1, Integer::sum);
    }

    /**
     * Resets monthly volume counters (called at billing cycle start).
     */
    public void resetMonthlyVolumes() {
        currentMonthlyVolumes.clear();
        log.info("Monthly volume counters reset for all rails");
    }

    /**
     * Registers a correspondent bank charge schedule.
     */
    public void registerCorrespondentCharge(CorrespondentCharge charge) {
        correspondentCharges.put(charge.correspondentBic(), charge);
        log.info("Correspondent charge registered: {} ({})", charge.correspondentName(), charge.correspondentBic());
    }

    /**
     * Overrides the default cost components for a rail.
     */
    public void configureCosts(PaymentRail rail, List<CostComponent> components) {
        baseCosts.put(rail, List.copyOf(components));
        log.info("Cost components updated for rail {}: {} components", rail, components.size());
    }

    /**
     * Configures volume-tier pricing for a rail.
     */
    public void configureVolumeTiers(PaymentRail rail, List<VolumeTier> tiers) {
        volumeTiers.put(rail, List.copyOf(tiers));
        log.info("Volume tiers configured for rail {}: {} tiers", rail, tiers.size());
    }

    private void initializeDefaultCosts() {
        // ACH costs: $0.20 flat + 0.5 bps
        baseCosts.put(PaymentRail.ACH, List.of(
                new CostComponent("NACHA network fee", CostType.FLAT_FEE,
                        new BigDecimal("0.06"), "Per-transaction NACHA fee"),
                new CostComponent("Originating bank fee", CostType.FLAT_FEE,
                        new BigDecimal("0.14"), "Internal processing fee"),
                new CostComponent("ACH variable fee", CostType.BASIS_POINTS,
                        new BigDecimal("0.50"), "Basis points on amount")
        ));

        // ACH volume tiers
        volumeTiers.put(PaymentRail.ACH, List.of(
                new VolumeTier(0, 10000, List.of(
                        new CostComponent("Volume surcharge", CostType.FLAT_FEE,
                                new BigDecimal("0.00"), "Base tier — no surcharge"))),
                new VolumeTier(10001, 50000, List.of(
                        new CostComponent("Volume discount", CostType.FLAT_FEE,
                                new BigDecimal("-0.03"), "Mid-tier discount"))),
                new VolumeTier(50001, Integer.MAX_VALUE, List.of(
                        new CostComponent("High-volume discount", CostType.FLAT_FEE,
                                new BigDecimal("-0.06"), "High-tier discount")))
        ));

        // Wire costs: $25.00 flat per domestic wire
        baseCosts.put(PaymentRail.WIRE, List.of(
                new CostComponent("Fedwire network fee", CostType.FLAT_FEE,
                        new BigDecimal("0.83"), "Per-transaction Fedwire fee"),
                new CostComponent("Wire processing fee", CostType.FLAT_FEE,
                        new BigDecimal("24.17"), "Internal wire processing")
        ));

        // RTP costs: $0.045 flat (TCH published rate) + 1 bp
        baseCosts.put(PaymentRail.RTP, List.of(
                new CostComponent("TCH network fee", CostType.FLAT_FEE,
                        new BigDecimal("0.045"), "Per-transaction TCH RTP fee"),
                new CostComponent("RTP processing fee", CostType.FLAT_FEE,
                        new BigDecimal("0.455"), "Internal RTP processing"),
                new CostComponent("RTP variable fee", CostType.BASIS_POINTS,
                        new BigDecimal("1.00"), "Basis points on amount")
        ));

        // FedNow costs: $0.045 flat (Fed published rate)
        baseCosts.put(PaymentRail.FEDNOW, List.of(
                new CostComponent("Fed network fee", CostType.FLAT_FEE,
                        new BigDecimal("0.045"), "Per-transaction FedNow fee"),
                new CostComponent("FedNow processing fee", CostType.FLAT_FEE,
                        new BigDecimal("0.405"), "Internal FedNow processing"),
                new CostComponent("FedNow variable fee", CostType.BASIS_POINTS,
                        new BigDecimal("0.75"), "Basis points on amount")
        ));

        // Book transfer: zero cost
        baseCosts.put(PaymentRail.BOOK, List.of());
    }
}
