package com.omnibank.lending.corporate.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Financial covenants (measurable ratios) and behavioral covenants (negative
 * pledges, no-sale-of-assets, etc.). Breach = default, which typically
 * triggers repricing, penalty rate, or acceleration.
 */
public sealed interface Covenant permits Covenant.Financial, Covenant.Behavioral {

    String id();
    LocalDate nextTestDate();

    record Financial(
            String id,
            Metric metric,
            Operator operator,
            BigDecimal threshold,
            LocalDate nextTestDate
    ) implements Covenant {
        public enum Metric {
            DEBT_SERVICE_COVERAGE,
            LEVERAGE_RATIO,
            FIXED_CHARGE_COVERAGE,
            MIN_TANGIBLE_NET_WORTH,
            CURRENT_RATIO
        }
        public enum Operator { GTE, LTE, GT, LT }
    }

    record Behavioral(
            String id,
            String description,
            LocalDate nextTestDate
    ) implements Covenant {}
}
