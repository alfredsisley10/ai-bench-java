package com.omnibank.payments.routing;

import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.RoutingNumber;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes payments to the optimal payment rail based on amount limits, speed
 * requirements, cost optimization, and recipient bank capabilities.
 *
 * <p>Routing is a multi-step decision process:
 * <ol>
 *   <li>Eligibility filtering — which rails can carry this payment at all?</li>
 *   <li>Capability check — does the beneficiary bank support the rail?</li>
 *   <li>Cost optimization — which eligible rail has the lowest total cost?</li>
 *   <li>Fallback chain — if the preferred rail is unavailable, what's next?</li>
 * </ol>
 *
 * <p>The engine maintains a bank capability directory (which banks support RTP,
 * FedNow, etc.) and allows configurable routing rules per payment type.
 */
public class PaymentRoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(PaymentRoutingEngine.class);

    private static final BigDecimal ACH_MAX = new BigDecimal("1000000.00");
    private static final BigDecimal RTP_MAX = new BigDecimal("1000000.00");
    private static final BigDecimal FEDNOW_MAX = new BigDecimal("500000.00");
    // Wire has no practical upper limit for our purposes

    public enum RoutingPreference {
        LOWEST_COST,
        FASTEST_SETTLEMENT,
        CUSTOMER_PREFERRED
    }

    public enum SettlementSpeed {
        IMMEDIATE(Duration.ZERO),
        SAME_DAY(Duration.ofHours(4)),
        NEXT_DAY(Duration.ofHours(24)),
        TWO_DAY(Duration.ofHours(48));

        private final Duration maxDuration;

        SettlementSpeed(Duration maxDuration) {
            this.maxDuration = maxDuration;
        }

        public Duration maxDuration() {
            return maxDuration;
        }
    }

    public record RoutingDecision(
            PaymentRail selectedRail,
            Money estimatedCost,
            SettlementSpeed estimatedSpeed,
            List<PaymentRail> fallbackChain,
            String routingReason,
            Instant decidedAt
    ) {}

    public record RoutingRule(
            String ruleId,
            String description,
            int priority,
            PaymentRail targetRail,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            SettlementSpeed requiredSpeed,
            boolean enabled
    ) implements Comparable<RoutingRule> {
        public RoutingRule {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(targetRail, "targetRail");
        }

        @Override
        public int compareTo(RoutingRule other) {
            return Integer.compare(this.priority, other.priority);
        }
    }

    public record BankCapability(
            RoutingNumber routingNumber,
            String bankName,
            Set<PaymentRail> supportedRails,
            boolean rtpParticipant,
            boolean fedNowParticipant,
            Instant lastUpdated
    ) {
        public BankCapability {
            Objects.requireNonNull(routingNumber, "routingNumber");
            Objects.requireNonNull(bankName, "bankName");
            Objects.requireNonNull(supportedRails, "supportedRails");
        }

        public boolean supports(PaymentRail rail) {
            return supportedRails.contains(rail);
        }
    }

    private final Clock clock;
    private final RailCostCalculator costCalculator;
    private final Map<String, BankCapability> bankDirectory = new ConcurrentHashMap<>();
    private final List<RoutingRule> routingRules = new ArrayList<>();

    public PaymentRoutingEngine(Clock clock, RailCostCalculator costCalculator) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.costCalculator = Objects.requireNonNull(costCalculator, "costCalculator");
        initializeDefaultRules();
    }

    /**
     * Routes a payment request to the optimal rail.
     * If the request specifies a rail explicitly (not null), validates eligibility
     * and returns that rail. Otherwise, determines the best rail automatically.
     */
    public RoutingDecision route(PaymentRequest request, RoutingPreference preference) {
        var now = Timestamp.now(clock);
        var amount = request.amount();

        log.info("Routing payment: amount={}, preference={}, requestedRail={}",
                amount, preference, request.rail());

        // If customer specified a rail, validate and use it
        if (request.rail() != null && request.rail() != PaymentRail.BOOK) {
            var eligibility = checkRailEligibility(request.rail(), amount, request.beneficiaryRouting().orElse(null));
            if (eligibility.isEmpty()) {
                var fallbacks = determineFallbackChain(request, preference);
                var cost = costCalculator.calculateCost(request.rail(), amount);
                return new RoutingDecision(
                        request.rail(), cost, estimateSpeed(request.rail()),
                        fallbacks, "Customer-selected rail", now);
            } else {
                log.warn("Customer-selected rail {} not eligible: {}. Applying fallback.",
                        request.rail(), eligibility.get());
                // Fall through to automatic routing
            }
        }

        // Book transfer detection — same-bank transfers
        if (request.rail() == PaymentRail.BOOK) {
            return new RoutingDecision(
                    PaymentRail.BOOK, Money.zero(CurrencyCode.USD), SettlementSpeed.IMMEDIATE,
                    List.of(), "Internal book transfer", now);
        }

        // Automatic routing
        var eligibleRails = findEligibleRails(request);

        if (eligibleRails.isEmpty()) {
            throw new NoEligibleRailException("No eligible rail found for payment: amount=%s, beneficiary=%s"
                    .formatted(amount, request.beneficiaryAccount()));
        }

        var selectedRail = selectOptimalRail(eligibleRails, amount, preference);
        var fallbacks = eligibleRails.stream()
                .filter(r -> r != selectedRail)
                .toList();
        var cost = costCalculator.calculateCost(selectedRail, amount);

        var reason = buildRoutingReason(selectedRail, preference, eligibleRails.size());

        log.info("Payment routed: rail={}, cost={}, speed={}, fallbacks={}",
                selectedRail, cost, estimateSpeed(selectedRail), fallbacks.size());

        return new RoutingDecision(selectedRail, cost, estimateSpeed(selectedRail),
                fallbacks, reason, now);
    }

    /**
     * Reroutes a payment to the next rail in the fallback chain.
     */
    public Optional<RoutingDecision> reroute(PaymentRequest request, PaymentRail failedRail,
                                              RoutingPreference preference) {
        var eligibleRails = findEligibleRails(request).stream()
                .filter(r -> r != failedRail)
                .toList();

        if (eligibleRails.isEmpty()) {
            log.warn("No fallback rails available after {} failure", failedRail);
            return Optional.empty();
        }

        var nextRail = selectOptimalRail(eligibleRails, request.amount(), preference);
        var cost = costCalculator.calculateCost(nextRail, request.amount());
        var remaining = eligibleRails.stream().filter(r -> r != nextRail).toList();

        var decision = new RoutingDecision(
                nextRail, cost, estimateSpeed(nextRail), remaining,
                "Fallback from %s".formatted(failedRail), Timestamp.now(clock));

        log.info("Payment rerouted from {} to {}: cost={}", failedRail, nextRail, cost);
        return Optional.of(decision);
    }

    /**
     * Registers a bank's capabilities in the directory.
     */
    public void registerBankCapability(BankCapability capability) {
        bankDirectory.put(capability.routingNumber().raw(), capability);
        log.info("Bank capability registered: {} ({}) — rails: {}",
                capability.bankName(), capability.routingNumber(), capability.supportedRails());
    }

    /**
     * Adds a custom routing rule.
     */
    public void addRoutingRule(RoutingRule rule) {
        routingRules.add(rule);
        routingRules.sort(Comparator.naturalOrder());
        log.info("Routing rule added: id={}, priority={}, rail={}", rule.ruleId(), rule.priority(), rule.targetRail());
    }

    private List<PaymentRail> findEligibleRails(PaymentRequest request) {
        var amount = request.amount();
        var beneficiaryRouting = request.beneficiaryRouting().orElse(null);
        var eligible = new ArrayList<PaymentRail>();

        for (var rail : PaymentRail.values()) {
            if (rail == PaymentRail.BOOK) continue; // Book is handled separately
            var ineligibilityReason = checkRailEligibility(rail, amount, beneficiaryRouting);
            if (ineligibilityReason.isEmpty()) {
                eligible.add(rail);
            } else {
                log.debug("Rail {} ineligible: {}", rail, ineligibilityReason.get());
            }
        }

        // Apply custom routing rules — rules can force or exclude rails
        for (var rule : routingRules) {
            if (!rule.enabled()) continue;
            var amtVal = amount.amount();
            if (rule.minAmount() != null && amtVal.compareTo(rule.minAmount()) < 0) continue;
            if (rule.maxAmount() != null && amtVal.compareTo(rule.maxAmount()) > 0) continue;

            if (!eligible.contains(rule.targetRail())) {
                // Rule suggests a rail that's not eligible — skip
                continue;
            }
            // Rules with higher priority move their rail to the front
            eligible.remove(rule.targetRail());
            eligible.addFirst(rule.targetRail());
        }

        return eligible;
    }

    private Optional<String> checkRailEligibility(PaymentRail rail, Money amount, RoutingNumber beneficiaryRouting) {
        var amtVal = amount.amount();

        return switch (rail) {
            case ACH -> {
                if (amtVal.compareTo(ACH_MAX) > 0)
                    yield Optional.of("Amount exceeds ACH limit of $1,000,000");
                yield Optional.empty();
            }
            case RTP -> {
                if (amtVal.compareTo(RTP_MAX) > 0)
                    yield Optional.of("Amount exceeds RTP limit of $1,000,000");
                if (beneficiaryRouting != null && !isBankRtpParticipant(beneficiaryRouting))
                    yield Optional.of("Beneficiary bank is not an RTP participant");
                yield Optional.empty();
            }
            case FEDNOW -> {
                if (amtVal.compareTo(FEDNOW_MAX) > 0)
                    yield Optional.of("Amount exceeds FedNow limit of $500,000");
                if (beneficiaryRouting != null && !isBankFedNowParticipant(beneficiaryRouting))
                    yield Optional.of("Beneficiary bank is not a FedNow participant");
                yield Optional.empty();
            }
            case WIRE -> Optional.empty(); // Wire accepts all amounts
            case BOOK -> Optional.of("Book transfer requires same-bank accounts");
        };
    }

    private PaymentRail selectOptimalRail(List<PaymentRail> eligible, Money amount, RoutingPreference pref) {
        return switch (pref) {
            case LOWEST_COST -> eligible.stream()
                    .min(Comparator.comparing(r -> costCalculator.calculateCost(r, amount).amount()))
                    .orElseThrow();
            case FASTEST_SETTLEMENT -> eligible.stream()
                    .min(Comparator.comparing(r -> estimateSpeed(r).maxDuration()))
                    .orElseThrow();
            case CUSTOMER_PREFERRED -> eligible.get(0); // First in list (rules may have reordered)
        };
    }

    private SettlementSpeed estimateSpeed(PaymentRail rail) {
        return switch (rail) {
            case RTP, FEDNOW, BOOK -> SettlementSpeed.IMMEDIATE;
            case WIRE -> SettlementSpeed.SAME_DAY;
            case ACH -> SettlementSpeed.NEXT_DAY; // Conservative estimate; same-day ACH may apply
        };
    }

    private boolean isBankRtpParticipant(RoutingNumber routing) {
        var cap = bankDirectory.get(routing.raw());
        return cap != null && cap.rtpParticipant();
    }

    private boolean isBankFedNowParticipant(RoutingNumber routing) {
        var cap = bankDirectory.get(routing.raw());
        return cap != null && cap.fedNowParticipant();
    }

    private List<PaymentRail> determineFallbackChain(PaymentRequest request, RoutingPreference pref) {
        return findEligibleRails(request).stream()
                .filter(r -> r != request.rail())
                .toList();
    }

    private String buildRoutingReason(PaymentRail rail, RoutingPreference pref, int eligibleCount) {
        return "Auto-routed to %s (%s preference, %d eligible rails)"
                .formatted(rail, pref, eligibleCount);
    }

    private void initializeDefaultRules() {
        // Default: prefer RTP for small instant payments
        routingRules.add(new RoutingRule(
                "DEFAULT_SMALL_INSTANT", "Prefer RTP for payments under $25,000",
                10, PaymentRail.RTP, null, new BigDecimal("25000.00"),
                SettlementSpeed.IMMEDIATE, true));

        // Default: prefer ACH for large non-urgent payments
        routingRules.add(new RoutingRule(
                "DEFAULT_LARGE_BATCH", "Prefer ACH for payments over $100,000",
                20, PaymentRail.ACH, new BigDecimal("100000.00"), null,
                SettlementSpeed.NEXT_DAY, true));
    }

    public static final class NoEligibleRailException extends RuntimeException {
        public NoEligibleRailException(String message) {
            super(message);
        }
    }
}
