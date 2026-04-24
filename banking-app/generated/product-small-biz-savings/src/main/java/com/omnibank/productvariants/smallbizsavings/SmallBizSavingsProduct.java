package com.omnibank.productvariants.smallbizsavings;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable descriptor for the Small Business Savings product. Values come from the
 * product catalog and are stamped into the domain layer once the product
 * is activated for a customer.
 */
public record SmallBizSavingsProduct(
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

    public static SmallBizSavingsProduct defaults() {
        return new SmallBizSavingsProduct(
                UUID.fromString("00000000-0000-0000-0000-242f77930000"),
                "SAV-SMALLBIZSAVINGS",
                "Small Business Savings",
                "SAVINGS",
                "SMALL_BUSINESS",
                "US",
                new BigDecimal("0.0150"),
                new BigDecimal("250.00"),
                new BigDecimal("5.00"),
                new BigDecimal("1000.00"),
                0,
                true,
                18,
                125
        );
    }

    public SmallBizSavingsProduct {
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
