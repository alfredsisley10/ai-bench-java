package com.omnibank.brand.rothira.omnidirect;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Brand-specific pricing adjustments relative to the national
 * posted rate. Digital-only brands bump rates to compete;
 * premium brands take a service-fee premium; community banks
 * offer relationship discounts.
 */
public final class RothIraOmnidirectPricingOverride {

    public BigDecimal apyBonusBps() {
        return switch ("OMNIDIRECT") {
            case "OMNIDIRECT" -> new BigDecimal("25");     // +0.25%
            case "LEGACYTRUST" -> new BigDecimal("15");    // +0.15%
            case "BLUEWATERBANK" -> new BigDecimal("10");  // +0.10%
            case "FUTUREBANK" -> new BigDecimal("5");
            case "STARTUPFI" -> new BigDecimal("20");
            case "SILVERBANK" -> BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    public BigDecimal monthlyFeeAdjustment() {
        return switch ("OMNIDIRECT") {
            case "OMNIDIRECT" -> new BigDecimal("-5.00"); // no monthly fee at all
            case "LEGACYTRUST" -> new BigDecimal("25.00"); // premium service fee
            case "SILVERBANK" -> new BigDecimal("-3.00");
            case "FUTUREBANK" -> new BigDecimal("-12.00"); // free for minors
            default -> BigDecimal.ZERO;
        };
    }

    public BigDecimal adjustApy(BigDecimal nationalApy) {
        BigDecimal bpsAsRate = apyBonusBps()
                .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_EVEN);
        return nationalApy.add(bpsAsRate);
    }

    public BigDecimal adjustMonthlyFee(BigDecimal national) {
        BigDecimal adj = national.add(monthlyFeeAdjustment());
        return adj.signum() < 0 ? BigDecimal.ZERO : adj;
    }

    public boolean isPromotionalRateActive() {
        return switch ("OMNIDIRECT") {
            case "OMNIDIRECT", "STARTUPFI" -> true;
            default -> false;
        };
    }
}
