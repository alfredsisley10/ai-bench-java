package com.omnibank.productvariants.jumbocd12month;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable descriptor for the Jumbo 12 Month CD product. Values come from the
 * product catalog and are stamped into the domain layer once the product
 * is activated for a customer.
 */
public record JumboCd12MonthProduct(
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

    public static JumboCd12MonthProduct defaults() {
        return new JumboCd12MonthProduct(
                UUID.fromString("00000000-0000-0000-0000-59b991ef0000"),
                "CER-JUMBOCD12MONTH",
                "Jumbo 12 Month CD",
                "CERTIFICATE",
                "HIGH_NET_WORTH",
                "US",
                new BigDecimal("0.0485"),
                new BigDecimal("100000.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                90,
                true,
                18,
                125
        );
    }

    public JumboCd12MonthProduct {
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
