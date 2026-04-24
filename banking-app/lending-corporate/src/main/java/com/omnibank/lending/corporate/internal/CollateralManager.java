package com.omnibank.lending.corporate.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages loan collateral across asset types: real estate, marketable securities,
 * accounts receivable, inventory, and equipment. Calculates loan-to-value (LTV)
 * ratios, tracks perfection status of security interests, and triggers margin
 * calls when collateral values fall below maintenance thresholds.
 *
 * <p>Collateral valuation is periodically refreshed. Real estate uses appraisals;
 * securities use market prices; receivables and inventory use aging and eligibility
 * criteria. Advance rates (haircuts) vary by collateral type and quality.
 */
public class CollateralManager {

    private static final Logger log = LoggerFactory.getLogger(CollateralManager.class);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    // ── Collateral types ──────────────────────────────────────────────────

    public enum CollateralType {
        REAL_ESTATE("0.75"),
        MARKETABLE_SECURITIES("0.80"),
        ACCOUNTS_RECEIVABLE("0.80"),
        INVENTORY("0.50"),
        EQUIPMENT("0.60"),
        CASH_DEPOSIT("0.95"),
        LETTER_OF_CREDIT("0.90"),
        INTELLECTUAL_PROPERTY("0.25"),
        OTHER("0.40");

        private final BigDecimal standardAdvanceRate;

        CollateralType(String rate) {
            this.standardAdvanceRate = new BigDecimal(rate);
        }

        /** Maximum percentage of collateral value that can be lent against. */
        public BigDecimal standardAdvanceRate() { return standardAdvanceRate; }
    }

    public enum PerfectionStatus {
        UNPERFECTED, FILED, PERFECTED, LAPSED, RELEASED
    }

    public enum ValuationMethod {
        APPRAISAL, MARKET_PRICE, BOOK_VALUE, DISCOUNTED_CASH_FLOW, FORMULA_BASED
    }

    // ── Value types ───────────────────────────────────────────────────────

    public record CollateralItem(
            UUID collateralId,
            UUID loanId,
            CollateralType type,
            String description,
            Money currentValue,
            Money originalValue,
            BigDecimal advanceRate,
            Money lendingValue,
            PerfectionStatus perfectionStatus,
            LocalDate perfectionDate,
            LocalDate lastValuationDate,
            ValuationMethod valuationMethod,
            Optional<LocalDate> nextValuationDue,
            Optional<String> filingReference
    ) {
        public CollateralItem {
            Objects.requireNonNull(collateralId, "collateralId");
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(currentValue, "currentValue");
            Objects.requireNonNull(originalValue, "originalValue");
            Objects.requireNonNull(advanceRate, "advanceRate");
            Objects.requireNonNull(lendingValue, "lendingValue");
            Objects.requireNonNull(perfectionStatus, "perfectionStatus");
            Objects.requireNonNull(lastValuationDate, "lastValuationDate");
            Objects.requireNonNull(valuationMethod, "valuationMethod");
            Objects.requireNonNull(nextValuationDue, "nextValuationDue");
            Objects.requireNonNull(filingReference, "filingReference");
        }
    }

    public record CollateralPortfolio(
            UUID loanId,
            List<CollateralItem> items,
            Money totalCurrentValue,
            Money totalLendingValue,
            Money loanOutstanding,
            BigDecimal loanToValue,
            BigDecimal collateralCoverageRatio,
            boolean isAdequate,
            Optional<MarginCallResult> marginCall
    ) {
        public CollateralPortfolio {
            Objects.requireNonNull(loanId, "loanId");
            items = List.copyOf(items);
            Objects.requireNonNull(totalCurrentValue, "totalCurrentValue");
            Objects.requireNonNull(totalLendingValue, "totalLendingValue");
            Objects.requireNonNull(loanOutstanding, "loanOutstanding");
            Objects.requireNonNull(loanToValue, "loanToValue");
            Objects.requireNonNull(collateralCoverageRatio, "collateralCoverageRatio");
            Objects.requireNonNull(marginCall, "marginCall");
        }
    }

    public record MarginCallResult(
            UUID loanId,
            LocalDate callDate,
            Money shortfall,
            BigDecimal currentLtv,
            BigDecimal requiredLtv,
            Money additionalCollateralRequired,
            LocalDate responseDeadline,
            String narrative
    ) {
        public MarginCallResult {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(callDate, "callDate");
            Objects.requireNonNull(shortfall, "shortfall");
            Objects.requireNonNull(currentLtv, "currentLtv");
            Objects.requireNonNull(requiredLtv, "requiredLtv");
            Objects.requireNonNull(additionalCollateralRequired, "additionalCollateralRequired");
            Objects.requireNonNull(responseDeadline, "responseDeadline");
        }
    }

    public record ValuationUpdate(
            UUID collateralId,
            Money newValue,
            ValuationMethod method,
            LocalDate valuationDate,
            String appraiserOrSource
    ) {
        public ValuationUpdate {
            Objects.requireNonNull(collateralId, "collateralId");
            Objects.requireNonNull(newValue, "newValue");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(valuationDate, "valuationDate");
        }
    }

    // ── Core calculations ─────────────────────────────────────────────────

    /**
     * Calculates the lending value of a collateral item by applying the
     * advance rate (haircut) to the current appraised value.
     */
    public static Money calculateLendingValue(Money currentValue, BigDecimal advanceRate) {
        return currentValue.times(advanceRate);
    }

    /**
     * Calculates the loan-to-value ratio: outstanding loan / total collateral value.
     * An LTV above the maintenance threshold triggers a margin call.
     */
    public static BigDecimal calculateLTV(Money loanOutstanding, Money totalCollateralValue) {
        if (totalCollateralValue.isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return loanOutstanding.amount()
                .divide(totalCollateralValue.amount(), MC);
    }

    /**
     * Calculates the collateral coverage ratio: total collateral value / outstanding loan.
     * Banks typically require a minimum coverage ratio of 1.0x to 1.5x.
     */
    public static BigDecimal calculateCoverageRatio(Money totalCollateralValue, Money loanOutstanding) {
        if (loanOutstanding.isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return totalCollateralValue.amount()
                .divide(loanOutstanding.amount(), MC);
    }

    /**
     * Determines the advance rate for a specific collateral item. The standard
     * rate may be adjusted based on quality, concentration, and seasoning.
     */
    public static BigDecimal determineAdvanceRate(
            CollateralType type,
            Optional<BigDecimal> overrideRate,
            BigDecimal concentrationFactor
    ) {
        BigDecimal baseRate = overrideRate.orElse(type.standardAdvanceRate());

        // Reduce advance rate for concentrated collateral pools
        if (concentrationFactor.compareTo(new BigDecimal("0.5")) > 0) {
            BigDecimal penalty = concentrationFactor.subtract(new BigDecimal("0.5"))
                    .multiply(new BigDecimal("0.10"), MC);
            baseRate = baseRate.subtract(penalty).max(new BigDecimal("0.10"));
        }

        return baseRate;
    }

    // ── Margin call logic ─────────────────────────────────────────────────

    /**
     * Evaluates whether a margin call is required based on current LTV vs.
     * the maintenance LTV threshold. Returns the margin call details if needed.
     */
    public static Optional<MarginCallResult> evaluateMarginCall(
            UUID loanId,
            Money loanOutstanding,
            Money totalCollateralValue,
            BigDecimal maintenanceLtv,
            int responseDeadlineDays
    ) {
        BigDecimal currentLtv = calculateLTV(loanOutstanding, totalCollateralValue);

        if (currentLtv.compareTo(maintenanceLtv) <= 0) {
            return Optional.empty();
        }

        // Calculate how much additional collateral is needed to restore LTV
        // Required: loanOutstanding / (totalCollateral + additional) = maintenanceLtv
        // additional = (loanOutstanding / maintenanceLtv) - totalCollateral
        Money requiredCollateral = Money.of(
                loanOutstanding.amount().divide(maintenanceLtv, MC),
                loanOutstanding.currency()
        );
        Money shortfall = requiredCollateral.minus(totalCollateralValue);

        LocalDate callDate = LocalDate.now();
        return Optional.of(new MarginCallResult(
                loanId, callDate, shortfall, currentLtv, maintenanceLtv,
                shortfall, callDate.plusDays(responseDeadlineDays),
                "Margin call triggered: current LTV %s exceeds maintenance threshold %s. Additional collateral of %s required."
                        .formatted(currentLtv.setScale(4, RoundingMode.HALF_EVEN),
                                maintenanceLtv.setScale(4, RoundingMode.HALF_EVEN),
                                shortfall)
        ));
    }

    // ── Portfolio assembly ─────────────────────────────────────────────────

    /**
     * Assembles a complete collateral portfolio view for a loan, including
     * aggregate values, LTV, coverage ratio, and margin call assessment.
     */
    public static CollateralPortfolio assemblePortfolio(
            UUID loanId,
            List<CollateralItem> items,
            Money loanOutstanding,
            BigDecimal maintenanceLtv,
            int marginCallResponseDays
    ) {
        CurrencyCode ccy = loanOutstanding.currency();
        Money totalCurrentValue = Money.zero(ccy);
        Money totalLendingValue = Money.zero(ccy);

        for (CollateralItem item : items) {
            totalCurrentValue = totalCurrentValue.plus(item.currentValue());
            totalLendingValue = totalLendingValue.plus(item.lendingValue());
        }

        BigDecimal ltv = calculateLTV(loanOutstanding, totalCurrentValue);
        BigDecimal coverageRatio = calculateCoverageRatio(totalCurrentValue, loanOutstanding);
        boolean adequate = ltv.compareTo(maintenanceLtv) <= 0;

        Optional<MarginCallResult> marginCall = evaluateMarginCall(
                loanId, loanOutstanding, totalCurrentValue,
                maintenanceLtv, marginCallResponseDays
        );

        return new CollateralPortfolio(
                loanId, items, totalCurrentValue, totalLendingValue,
                loanOutstanding, ltv, coverageRatio, adequate, marginCall
        );
    }

    // ── Perfection tracking ───────────────────────────────────────────────

    /**
     * Determines the next required action for collateral perfection based on
     * the current status and filing dates.
     */
    public static Optional<String> nextPerfectionAction(
            PerfectionStatus status,
            Optional<LocalDate> filingExpiryDate,
            LocalDate asOf
    ) {
        return switch (status) {
            case UNPERFECTED -> Optional.of("File UCC-1 financing statement or equivalent");
            case FILED -> Optional.of("Confirm perfection with filing office");
            case PERFECTED -> {
                if (filingExpiryDate.isPresent()) {
                    long daysToExpiry = ChronoUnit.DAYS.between(asOf, filingExpiryDate.get());
                    if (daysToExpiry <= 180) {
                        yield Optional.of("File UCC-3 continuation statement within %d days"
                                .formatted(daysToExpiry));
                    }
                }
                yield Optional.empty();
            }
            case LAPSED -> Optional.of("URGENT: Re-file UCC-1 — security interest has lapsed");
            case RELEASED -> Optional.empty();
        };
    }

    /**
     * Determines if a collateral revaluation is due based on the type and
     * the time since the last valuation.
     */
    public static boolean isRevaluationDue(CollateralType type, LocalDate lastValuation, LocalDate asOf) {
        long daysSinceValuation = ChronoUnit.DAYS.between(lastValuation, asOf);
        long maxDays = switch (type) {
            case MARKETABLE_SECURITIES -> 1;    // Daily mark-to-market
            case ACCOUNTS_RECEIVABLE -> 30;     // Monthly aging report
            case INVENTORY -> 90;               // Quarterly field exam
            case REAL_ESTATE -> 365;            // Annual appraisal
            case EQUIPMENT -> 365;              // Annual appraisal
            case CASH_DEPOSIT -> 1;             // Daily
            case LETTER_OF_CREDIT -> 30;        // Monthly
            case INTELLECTUAL_PROPERTY -> 365;  // Annual
            case OTHER -> 180;                  // Semi-annual
        };
        return daysSinceValuation >= maxDays;
    }

    /**
     * Calculates the weighted-average advance rate for a portfolio of collateral.
     */
    public static BigDecimal weightedAverageAdvanceRate(List<CollateralItem> items) {
        if (items.isEmpty()) return BigDecimal.ZERO;

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;

        for (CollateralItem item : items) {
            weightedSum = weightedSum.add(
                    item.currentValue().amount().multiply(item.advanceRate(), MC));
            totalValue = totalValue.add(item.currentValue().amount());
        }

        if (totalValue.signum() == 0) return BigDecimal.ZERO;
        return weightedSum.divide(totalValue, MC);
    }
}
