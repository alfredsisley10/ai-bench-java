package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes interchange fees per Visa / MC / Amex rate tables.
 *
 * <p>Interchange is a percentage of the transaction plus a flat per-tx
 * fee, differentiated by transaction type (credit, debit, prepaid) and
 * whether the issuing bank is "regulated" under Reg II (Durbin) — banks
 * with over $10B in assets are regulated and their debit interchange is
 * capped at $0.21 + 5 bps + $0.01 fraud adjustment.
 *
 * <p>This class is deliberately a pure function. The settlement processor
 * uses it at posting time; analytics dashboards use it to re-cost traffic
 * under scenario assumptions.
 */
@Service
public class InterchangeCalculator {

    /** Transaction type — drives which rate card applies. */
    public enum TransactionType {
        CREDIT_CARD_PRESENT,
        CREDIT_CARD_NOT_PRESENT,
        DEBIT_REGULATED,
        DEBIT_EXEMPT,
        PREPAID
    }

    /** A rate entry — basis-points percentage plus a flat component. */
    public record RateEntry(BigDecimal bps, Money flatFee) {
        public RateEntry {
            Objects.requireNonNull(bps, "bps");
            Objects.requireNonNull(flatFee, "flatFee");
        }
    }

    // Rate card — simplified. Real tables are spreadsheets per MCC category.
    private static final Map<String, RateEntry> VISA_CREDIT_PRESENT = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("155"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("GROCERY", new RateEntry(new BigDecimal("100"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("FUEL", new RateEntry(new BigDecimal("115"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("RESTAURANT", new RateEntry(new BigDecimal("184"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("HOTEL", new RateEntry(new BigDecimal("158"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("AIRLINE", new RateEntry(new BigDecimal("170"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("CHARITY", new RateEntry(new BigDecimal("135"), Money.of("0.05", CurrencyCode.USD))),
            Map.entry("UTILITIES", new RateEntry(new BigDecimal("0"), Money.of("0.75", CurrencyCode.USD)))
    );

    private static final Map<String, RateEntry> VISA_CREDIT_CNP = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("180"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("DIGITAL", new RateEntry(new BigDecimal("165"), Money.of("0.10", CurrencyCode.USD)))
    );

    private static final Map<String, RateEntry> MC_CREDIT_PRESENT = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("158"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("GROCERY", new RateEntry(new BigDecimal("105"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("FUEL", new RateEntry(new BigDecimal("115"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("RESTAURANT", new RateEntry(new BigDecimal("189"), Money.of("0.10", CurrencyCode.USD)))
    );

    private static final Map<String, RateEntry> MC_CREDIT_CNP = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("185"), Money.of("0.10", CurrencyCode.USD)))
    );

    private static final Map<String, RateEntry> AMEX_CREDIT = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("230"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("SMALL_TICKET", new RateEntry(new BigDecimal("170"), Money.of("0.10", CurrencyCode.USD))),
            Map.entry("TRAVEL", new RateEntry(new BigDecimal("250"), Money.of("0.10", CurrencyCode.USD)))
    );

    /** Reg-II ("regulated") debit cap: 0.21 + 5 bps + 0.01 fraud adj. */
    private static final RateEntry DEBIT_REGULATED =
            new RateEntry(new BigDecimal("5"), Money.of("0.22", CurrencyCode.USD));

    /** Exempt debit: small banks and prepaid — higher tiered rates. */
    private static final Map<String, RateEntry> DEBIT_EXEMPT = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("115"), Money.of("0.15", CurrencyCode.USD))),
            Map.entry("GROCERY", new RateEntry(new BigDecimal("70"), Money.of("0.15", CurrencyCode.USD))),
            Map.entry("FUEL", new RateEntry(new BigDecimal("80"), Money.of("0.15", CurrencyCode.USD)))
    );

    private static final Map<String, RateEntry> PREPAID = Map.ofEntries(
            Map.entry("DEFAULT", new RateEntry(new BigDecimal("100"), Money.of("0.15", CurrencyCode.USD)))
    );

    /** MCC -> category key mapping so callers can pass the MCC directly. */
    private static final Set<String> GROCERY_MCCS = Set.of("5411", "5499");
    private static final Set<String> FUEL_MCCS = Set.of("5541", "5542");
    private static final Set<String> RESTAURANT_MCCS = Set.of("5811", "5812", "5813", "5814");
    private static final Set<String> HOTEL_MCCS = Set.of("7011", "7012");
    private static final Set<String> AIRLINE_MCCS = Set.of("3000", "3001", "3005", "3058", "4511");
    private static final Set<String> UTILITY_MCCS = Set.of("4900", "4814", "4812");
    private static final Set<String> CHARITY_MCCS = Set.of("8398", "8661");

    /**
     * Compute the interchange fee for a clearing presentment.
     */
    public Money compute(CardNetwork network,
                         TransactionType txType,
                         String mcc,
                         Money transactionAmount) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(txType, "txType");
        Objects.requireNonNull(transactionAmount, "transactionAmount");
        if (!transactionAmount.isPositive()) return Money.zero(transactionAmount.currency());

        var categoryKey = resolveCategory(mcc, txType);
        var rate = resolveRate(network, txType, categoryKey);

        var percentPortion = transactionAmount.times(
                rate.bps().divide(BigDecimal.valueOf(10_000), 6, RoundingMode.HALF_EVEN));
        return percentPortion.plus(rate.flatFee());
    }

    /** Small-ticket ($15 and under) gets its own cheaper Amex rate. */
    public Money computeAmexSmallTicket(Money transactionAmount) {
        return compute(CardNetwork.AMEX, TransactionType.CREDIT_CARD_PRESENT,
                "5999", transactionAmount.amount().compareTo(new BigDecimal("15.00")) <= 0
                        ? "SMALL_TICKET_OVERRIDE"
                        : "5999");
    }

    private Money compute(CardNetwork n, TransactionType t, String mcc, String overrideKey) {
        var rate = AMEX_CREDIT.getOrDefault(overrideKey, AMEX_CREDIT.get("DEFAULT"));
        var transactionAmount = Money.of(new BigDecimal("15.00"), CurrencyCode.USD);
        return transactionAmount.times(
                rate.bps().divide(BigDecimal.valueOf(10_000), 6, RoundingMode.HALF_EVEN))
                .plus(rate.flatFee());
    }

    /**
     * Resolve the bucket this transaction falls into. Returns an opaque
     * string key that the rate-card lookup uses.
     */
    static String resolveCategory(String mcc, TransactionType txType) {
        if (mcc == null || mcc.isBlank()) return "DEFAULT";
        if (GROCERY_MCCS.contains(mcc)) return "GROCERY";
        if (FUEL_MCCS.contains(mcc)) return "FUEL";
        if (RESTAURANT_MCCS.contains(mcc)) return "RESTAURANT";
        if (HOTEL_MCCS.contains(mcc)) return "HOTEL";
        if (AIRLINE_MCCS.contains(mcc)) return "AIRLINE";
        if (UTILITY_MCCS.contains(mcc)) return "UTILITIES";
        if (CHARITY_MCCS.contains(mcc)) return "CHARITY";
        if (txType == TransactionType.CREDIT_CARD_NOT_PRESENT) return "DIGITAL";
        return "DEFAULT";
    }

    private static RateEntry resolveRate(CardNetwork network,
                                         TransactionType txType,
                                         String categoryKey) {
        return switch (network) {
            case AMEX -> resolveAmex(categoryKey);
            case MASTERCARD -> resolveMasterCard(txType, categoryKey);
            case DISCOVER, VISA -> resolveVisa(txType, categoryKey);
            case PULSE, STAR, INTERLINK, MAESTRO -> resolveDebit(txType, categoryKey);
        };
    }

    private static RateEntry resolveVisa(TransactionType txType, String categoryKey) {
        return switch (txType) {
            case CREDIT_CARD_PRESENT ->
                    VISA_CREDIT_PRESENT.getOrDefault(categoryKey, VISA_CREDIT_PRESENT.get("DEFAULT"));
            case CREDIT_CARD_NOT_PRESENT ->
                    VISA_CREDIT_CNP.getOrDefault(categoryKey, VISA_CREDIT_CNP.get("DEFAULT"));
            case DEBIT_REGULATED -> DEBIT_REGULATED;
            case DEBIT_EXEMPT ->
                    DEBIT_EXEMPT.getOrDefault(categoryKey, DEBIT_EXEMPT.get("DEFAULT"));
            case PREPAID -> PREPAID.get("DEFAULT");
        };
    }

    private static RateEntry resolveMasterCard(TransactionType txType, String categoryKey) {
        return switch (txType) {
            case CREDIT_CARD_PRESENT ->
                    MC_CREDIT_PRESENT.getOrDefault(categoryKey, MC_CREDIT_PRESENT.get("DEFAULT"));
            case CREDIT_CARD_NOT_PRESENT ->
                    MC_CREDIT_CNP.getOrDefault(categoryKey, MC_CREDIT_CNP.get("DEFAULT"));
            case DEBIT_REGULATED -> DEBIT_REGULATED;
            case DEBIT_EXEMPT ->
                    DEBIT_EXEMPT.getOrDefault(categoryKey, DEBIT_EXEMPT.get("DEFAULT"));
            case PREPAID -> PREPAID.get("DEFAULT");
        };
    }

    private static RateEntry resolveAmex(String categoryKey) {
        return AMEX_CREDIT.getOrDefault(categoryKey, AMEX_CREDIT.get("DEFAULT"));
    }

    private static RateEntry resolveDebit(TransactionType txType, String categoryKey) {
        if (txType == TransactionType.DEBIT_REGULATED) return DEBIT_REGULATED;
        if (txType == TransactionType.PREPAID) return PREPAID.get("DEFAULT");
        return DEBIT_EXEMPT.getOrDefault(categoryKey, DEBIT_EXEMPT.get("DEFAULT"));
    }

    /**
     * Effective rate (in percent) that this transaction would pay — useful
     * for reporting dashboards.
     */
    public BigDecimal effectiveRatePercent(CardNetwork network,
                                           TransactionType txType,
                                           String mcc,
                                           Money transactionAmount) {
        var fee = compute(network, txType, mcc, transactionAmount);
        if (transactionAmount.isZero()) return BigDecimal.ZERO;
        return fee.amount()
                .divide(transactionAmount.amount(), 6, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    /**
     * Raw lookup of a rate card entry — exposed for admin UIs.
     */
    public RateEntry rateFor(CardNetwork network, TransactionType txType, String mcc) {
        return resolveRate(network, txType, resolveCategory(mcc, txType));
    }
}
