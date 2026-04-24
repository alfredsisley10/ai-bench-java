package com.omnibank.lending.corporate.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity for credit facilities: revolving, term, and delayed-draw.
 * Supports sublimits (e.g., a letter of credit sublimit within a revolver),
 * multi-currency options, and pricing grids that adjust the spread based on
 * leverage or utilization levels.
 *
 * <p>A facility represents the contractual framework within which individual
 * loans (draws) are made. One credit agreement can contain multiple facilities.
 */
@Entity
@Table(name = "credit_facility", schema = "lending_corporate")
public class FacilityEntity extends AuditableEntity {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "credit_agreement_id", nullable = false)
    private UUID creditAgreementId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    @Column(name = "facility_name", nullable = false, length = 128)
    private String facilityName;

    @Enumerated(EnumType.STRING)
    @Column(name = "facility_type", nullable = false, length = 20)
    private FacilityType facilityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FacilityStatus status;

    @Column(name = "total_commitment", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalCommitment;

    @Column(name = "available_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal availableAmount;

    @Column(name = "utilized_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal utilizedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_currency", nullable = false, length = 3)
    private CurrencyCode baseCurrency;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "availability_end_date", nullable = false)
    private LocalDate availabilityEndDate;

    // ── Interest rate terms ───────────────────────────────────────────────

    @Column(name = "base_rate_type", nullable = false, length = 16)
    private String baseRateType; // "FIXED", "SOFR", "EURIBOR", "PRIME"

    @Column(name = "credit_spread_bps", nullable = false)
    private long creditSpreadBps;

    @Column(name = "floor_rate_bps")
    private Long floorRateBps;

    @Column(name = "commitment_fee_bps", nullable = false)
    private long commitmentFeeBps;

    @Column(name = "utilization_fee_bps")
    private Long utilizationFeeBps;

    // ── Sublimits ─────────────────────────────────────────────────────────

    @Column(name = "lc_sublimit", precision = 19, scale = 4)
    private BigDecimal letterOfCreditSublimit;

    @Column(name = "swingline_sublimit", precision = 19, scale = 4)
    private BigDecimal swinglineSublimit;

    @Column(name = "competitive_bid_sublimit", precision = 19, scale = 4)
    private BigDecimal competitiveBidSublimit;

    @Column(name = "lc_utilized", precision = 19, scale = 4)
    private BigDecimal letterOfCreditUtilized;

    @Column(name = "swingline_utilized", precision = 19, scale = 4)
    private BigDecimal swinglineUtilized;

    // ── Multi-currency support ────────────────────────────────────────────

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "facility_currency_option",
            schema = "lending_corporate",
            joinColumns = @JoinColumn(name = "facility_id")
    )
    @Column(name = "currency_code", length = 3)
    @Enumerated(EnumType.STRING)
    private List<CurrencyCode> allowedCurrencies = new ArrayList<>();

    // ── Pricing grid ──────────────────────────────────────────────────────

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "facility_pricing_tier",
            schema = "lending_corporate",
            joinColumns = @JoinColumn(name = "facility_id")
    )
    private List<PricingTier> pricingGrid = new ArrayList<>();

    @Column(name = "minimum_draw_amount", precision = 19, scale = 4)
    private BigDecimal minimumDrawAmount;

    @Column(name = "draw_increment", precision = 19, scale = 4)
    private BigDecimal drawIncrement;

    @Column(name = "prepayment_notice_days")
    private Integer prepaymentNoticeDays;

    @Column(name = "extension_option_count")
    private Integer extensionOptionCount;

    @Column(name = "extension_term_months")
    private Integer extensionTermMonths;

    public enum FacilityType { REVOLVING, TERM, DELAYED_DRAW, BRIDGE, SWINGLINE }

    public enum FacilityStatus { COMMITTED, ACTIVE, AMENDED, EXTENDED, REDUCED, TERMINATED, EXPIRED }

    protected FacilityEntity() {}

    public FacilityEntity(UUID id, UUID creditAgreementId, UUID borrowerId,
                          String facilityName, FacilityType facilityType,
                          BigDecimal totalCommitment, CurrencyCode baseCurrency,
                          LocalDate effectiveDate, LocalDate maturityDate,
                          LocalDate availabilityEndDate,
                          String baseRateType, long creditSpreadBps,
                          long commitmentFeeBps) {
        this.id = id;
        this.creditAgreementId = creditAgreementId;
        this.borrowerId = borrowerId;
        this.facilityName = facilityName;
        this.facilityType = facilityType;
        this.status = FacilityStatus.COMMITTED;
        this.totalCommitment = totalCommitment;
        this.availableAmount = totalCommitment;
        this.utilizedAmount = BigDecimal.ZERO;
        this.baseCurrency = baseCurrency;
        this.effectiveDate = effectiveDate;
        this.maturityDate = maturityDate;
        this.availabilityEndDate = availabilityEndDate;
        this.baseRateType = baseRateType;
        this.creditSpreadBps = creditSpreadBps;
        this.commitmentFeeBps = commitmentFeeBps;
        this.letterOfCreditUtilized = BigDecimal.ZERO;
        this.swinglineUtilized = BigDecimal.ZERO;
    }

    // ── Business methods ──────────────────────────────────────────────────

    public void activate() {
        if (status != FacilityStatus.COMMITTED) {
            throw new IllegalStateException("Can only activate a committed facility, current: " + status);
        }
        this.status = FacilityStatus.ACTIVE;
    }

    public void recordDraw(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Draw amount must be positive");
        }
        if (amount.compareTo(availableAmount) > 0) {
            throw new IllegalStateException(
                    "Draw amount %s exceeds available %s".formatted(amount, availableAmount));
        }
        if (minimumDrawAmount != null && amount.compareTo(minimumDrawAmount) < 0) {
            throw new IllegalArgumentException(
                    "Draw amount %s below minimum %s".formatted(amount, minimumDrawAmount));
        }
        this.utilizedAmount = this.utilizedAmount.add(amount);
        this.availableAmount = this.totalCommitment.subtract(this.utilizedAmount);
    }

    public void recordRepayment(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Repayment amount must be positive");
        }
        if (amount.compareTo(utilizedAmount) > 0) {
            throw new IllegalStateException(
                    "Repayment %s exceeds outstanding %s".formatted(amount, utilizedAmount));
        }
        this.utilizedAmount = this.utilizedAmount.subtract(amount);
        if (facilityType == FacilityType.REVOLVING || facilityType == FacilityType.SWINGLINE) {
            this.availableAmount = this.totalCommitment.subtract(this.utilizedAmount);
        }
    }

    public void recordLetterOfCreditIssuance(BigDecimal amount) {
        if (letterOfCreditSublimit == null) {
            throw new IllegalStateException("Facility does not have an LC sublimit");
        }
        BigDecimal newLcUtilized = letterOfCreditUtilized.add(amount);
        if (newLcUtilized.compareTo(letterOfCreditSublimit) > 0) {
            throw new IllegalStateException(
                    "LC utilization %s would exceed sublimit %s".formatted(newLcUtilized, letterOfCreditSublimit));
        }
        this.letterOfCreditUtilized = newLcUtilized;
        recordDraw(amount); // LCs count against overall utilization
    }

    public void reduceCommitment(BigDecimal reductionAmount) {
        Objects.requireNonNull(reductionAmount, "reductionAmount");
        BigDecimal newCommitment = totalCommitment.subtract(reductionAmount);
        if (newCommitment.compareTo(utilizedAmount) < 0) {
            throw new IllegalStateException(
                    "Cannot reduce commitment to %s below utilization %s"
                            .formatted(newCommitment, utilizedAmount));
        }
        this.totalCommitment = newCommitment;
        this.availableAmount = newCommitment.subtract(utilizedAmount);
        this.status = FacilityStatus.REDUCED;
    }

    public void extendMaturity(int months) {
        if (extensionOptionCount == null || extensionOptionCount <= 0) {
            throw new IllegalStateException("No extension options remaining");
        }
        this.maturityDate = this.maturityDate.plusMonths(
                extensionTermMonths != null ? extensionTermMonths : months);
        this.extensionOptionCount = this.extensionOptionCount - 1;
        this.status = FacilityStatus.EXTENDED;
    }

    public void terminate() {
        if (utilizedAmount.signum() > 0) {
            throw new IllegalStateException(
                    "Cannot terminate facility with outstanding utilization: " + utilizedAmount);
        }
        this.status = FacilityStatus.TERMINATED;
        this.availableAmount = BigDecimal.ZERO;
    }

    public BigDecimal utilizationRate() {
        if (totalCommitment.signum() == 0) return BigDecimal.ZERO;
        return utilizedAmount.divide(totalCommitment, MC);
    }

    /**
     * Resolves the applicable credit spread from the pricing grid based on
     * the current leverage ratio. Falls back to the base spread if no grid
     * tier matches.
     */
    public long resolveSpreadBps(BigDecimal leverageRatio) {
        if (pricingGrid.isEmpty()) {
            return creditSpreadBps;
        }
        return pricingGrid.stream()
                .filter(tier -> leverageRatio.compareTo(tier.maxLeverageRatio()) <= 0)
                .mapToLong(PricingTier::spreadBps)
                .findFirst()
                .orElse(creditSpreadBps);
    }

    public boolean isExpired(LocalDate asOf) {
        return asOf.isAfter(maturityDate);
    }

    public boolean isAvailable(LocalDate asOf) {
        return status == FacilityStatus.ACTIVE
                && !asOf.isBefore(effectiveDate)
                && !asOf.isAfter(availabilityEndDate);
    }

    public void setSublimits(BigDecimal lcSublimit, BigDecimal swinglineSublimit,
                             BigDecimal competitiveBidSublimit) {
        this.letterOfCreditSublimit = lcSublimit;
        this.swinglineSublimit = swinglineSublimit;
        this.competitiveBidSublimit = competitiveBidSublimit;
    }

    public void setDrawConstraints(BigDecimal minimumDraw, BigDecimal increment, int noticeDays) {
        this.minimumDrawAmount = minimumDraw;
        this.drawIncrement = increment;
        this.prepaymentNoticeDays = noticeDays;
    }

    public void setExtensionOption(int optionCount, int termMonths) {
        this.extensionOptionCount = optionCount;
        this.extensionTermMonths = termMonths;
    }

    public void addAllowedCurrency(CurrencyCode currency) {
        if (!allowedCurrencies.contains(currency)) {
            allowedCurrencies.add(currency);
        }
    }

    public void addPricingTier(PricingTier tier) {
        pricingGrid.add(tier);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public UUID id() { return id; }
    public UUID creditAgreementId() { return creditAgreementId; }
    public UUID borrowerId() { return borrowerId; }
    public String facilityName() { return facilityName; }
    public FacilityType facilityType() { return facilityType; }
    public FacilityStatus status() { return status; }
    public BigDecimal totalCommitment() { return totalCommitment; }
    public BigDecimal availableAmount() { return availableAmount; }
    public BigDecimal utilizedAmount() { return utilizedAmount; }
    public CurrencyCode baseCurrency() { return baseCurrency; }
    public LocalDate effectiveDate() { return effectiveDate; }
    public LocalDate maturityDate() { return maturityDate; }
    public LocalDate availabilityEndDate() { return availabilityEndDate; }
    public String baseRateType() { return baseRateType; }
    public long creditSpreadBps() { return creditSpreadBps; }
    public Long floorRateBps() { return floorRateBps; }
    public long commitmentFeeBps() { return commitmentFeeBps; }
    public Long utilizationFeeBps() { return utilizationFeeBps; }
    public BigDecimal letterOfCreditSublimit() { return letterOfCreditSublimit; }
    public BigDecimal swinglineSublimit() { return swinglineSublimit; }
    public BigDecimal letterOfCreditUtilized() { return letterOfCreditUtilized; }
    public List<CurrencyCode> allowedCurrencies() { return Collections.unmodifiableList(allowedCurrencies); }
    public List<PricingTier> pricingGrid() { return Collections.unmodifiableList(pricingGrid); }
    public BigDecimal minimumDrawAmount() { return minimumDrawAmount; }
    public Integer prepaymentNoticeDays() { return prepaymentNoticeDays; }
    public Integer extensionOptionCount() { return extensionOptionCount; }
}
