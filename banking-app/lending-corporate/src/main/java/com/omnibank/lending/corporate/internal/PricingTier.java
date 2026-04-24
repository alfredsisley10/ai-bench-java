package com.omnibank.lending.corporate.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Embeddable pricing tier used in facility pricing grids. Each tier maps a
 * leverage ratio range to a credit spread (in basis points). As the borrower's
 * leverage changes, the applicable spread steps up or down through the grid.
 *
 * <p>Example grid:
 * <pre>
 *   Leverage <= 2.0x : 150 bps
 *   Leverage <= 3.0x : 200 bps
 *   Leverage <= 4.0x : 275 bps
 *   Leverage >  4.0x : 350 bps
 * </pre>
 */
@Embeddable
public class PricingTier {

    @Column(name = "max_leverage_ratio", precision = 8, scale = 4, nullable = false)
    private BigDecimal maxLeverageRatio;

    @Column(name = "spread_bps", nullable = false)
    private long spreadBps;

    @Column(name = "commitment_fee_bps")
    private Long commitmentFeeBps;

    protected PricingTier() {}

    public PricingTier(BigDecimal maxLeverageRatio, long spreadBps) {
        this.maxLeverageRatio = Objects.requireNonNull(maxLeverageRatio, "maxLeverageRatio");
        this.spreadBps = spreadBps;
    }

    public PricingTier(BigDecimal maxLeverageRatio, long spreadBps, long commitmentFeeBps) {
        this(maxLeverageRatio, spreadBps);
        this.commitmentFeeBps = commitmentFeeBps;
    }

    public BigDecimal maxLeverageRatio() { return maxLeverageRatio; }
    public long spreadBps() { return spreadBps; }
    public Long commitmentFeeBps() { return commitmentFeeBps; }
}
