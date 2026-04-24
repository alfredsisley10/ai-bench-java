package com.omnibank.productvariants.nonprofitchecking;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable descriptor for the Non-Profit Business Checking product. Values come from the
 * product catalog and are stamped into the domain layer once the product
 * is activated for a customer.
 */
public record NonProfitCheckingProduct(
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

    public static NonProfitCheckingProduct defaults() {
        return new NonProfitCheckingProduct(
                UUID.fromString("00000000-0000-0000-0000-33ada04b0000"),
                "CHE-NONPROFITCHECKING",
                "Non-Profit Business Checking",
                "CHECKING",
                "BUSINESS",
                "US",
                new BigDecimal("0.0025"),
                new BigDecimal("0.00"),
                new BigDecimal("8.00"),
                new BigDecimal("2500.00"),
                0,
                true,
                18,
                125
        );
    }

    public NonProfitCheckingProduct {
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
