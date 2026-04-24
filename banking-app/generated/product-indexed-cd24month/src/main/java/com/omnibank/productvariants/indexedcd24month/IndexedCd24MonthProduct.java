package com.omnibank.productvariants.indexedcd24month;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable descriptor for the Market-Indexed 24 Month CD product. Values come from the
 * product catalog and are stamped into the domain layer once the product
 * is activated for a customer.
 */
public record IndexedCd24MonthProduct(
        UUID productId,
        String productCode,
        String displayName,
        String category,
        String targetSegment,
        String jurisdiction,
        BigDecimal baseRate,
        BigDecimal minBalance,
        BigDecimal monthlyFee,
        BigDecimal feeWaiverBalance,
        int earlyWithdrawalPenaltyDays,
        boolean requiresId,
        int eligibilityAgeMin,
        int eligibilityAgeMax
) {

    public static IndexedCd24MonthProduct defaults() {
        return new IndexedCd24MonthProduct(
                UUID.fromString("00000000-0000-0000-0000-1d67d40c0000"),
                "CER-INDEXEDCD24MONTH",
                "Market-Indexed 24 Month CD",
                "CERTIFICATE",
                "HIGH_NET_WORTH",
                "US",
                new BigDecimal("0.0350"),
                new BigDecimal("10000.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                180,
                true,
                18,
                125
        );
    }

    public IndexedCd24MonthProduct {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(baseRate, "baseRate");
        if (baseRate.signum() < 0) {
            throw new IllegalArgumentException("baseRate cannot be negative");
        }
        if (minBalance.signum() < 0) {
            throw new IllegalArgumentException("minBalance cannot be negative");
        }
        if (eligibilityAgeMin < 0 || eligibilityAgeMax < eligibilityAgeMin) {
            throw new IllegalArgumentException("invalid eligibility age range");
        }
    }

    public boolean isInterestBearing() {
        return baseRate.signum() > 0;
    }

    public boolean isDepositAccount() {
        return switch (category) {
            case "CHECKING", "SAVINGS", "MONEY_MARKET", "CERTIFICATE" -> true;
            default -> false;
        };
    }

    public boolean requiresEarlyWithdrawalPenalty() {
        return earlyWithdrawalPenaltyDays > 0;
    }
}
