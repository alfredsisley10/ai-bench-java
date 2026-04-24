package com.omnibank.regional.essentialchecking.ct;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Jurisdiction profile for EssentialChecking as offered in Connecticut (CT).
 * Captures state-specific overrides that diverge from the
 * national product specification.
 */
public final class EssentialCheckingCTProfile {

    private final String stateCode;
    private final String stateName;
    private final String region;
    private final BigDecimal salesTaxRate;
    private final String fdicRegion;
    private final int escheatmentDormancyYears;
    private final boolean offersSpanishLanguageSupport;
    private final boolean requiresPrintedDisclosure;
    private final String stateBankingAuthority;

    public EssentialCheckingCTProfile() {
        this.stateCode = "CT";
        this.stateName = "Connecticut";
        this.region = "NORTHEAST";
        this.salesTaxRate = new BigDecimal("0.0635");
        this.fdicRegion = "NEW_YORK";
        this.escheatmentDormancyYears = defaultEscheatmentYears();
        this.offersSpanishLanguageSupport = offersSpanish();
        this.requiresPrintedDisclosure = printedDisclosureRequired();
        this.stateBankingAuthority = stateAuthorityName();
    }

    public String stateCode() { return stateCode; }
    public String stateName() { return stateName; }
    public String region() { return region; }
    public BigDecimal salesTaxRate() { return salesTaxRate; }
    public String fdicRegion() { return fdicRegion; }
    public int escheatmentDormancyYears() { return escheatmentDormancyYears; }
    public boolean offersSpanishLanguageSupport() { return offersSpanishLanguageSupport; }
    public boolean requiresPrintedDisclosure() { return requiresPrintedDisclosure; }
    public String stateBankingAuthority() { return stateBankingAuthority; }

    public boolean isNoSalesTaxState() {
        return salesTaxRate.signum() == 0;
    }

    public BigDecimal applyStateFeeTax(BigDecimal baseFee) {
        Objects.requireNonNull(baseFee, "baseFee");
        return baseFee.multiply(BigDecimal.ONE.add(salesTaxRate))
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    private int defaultEscheatmentYears() {
        return switch (stateCode) {
            case "DE" -> 5;
            case "CA", "FL", "TX", "NY", "IL" -> 3;
            default -> 5;
        };
    }

    private boolean offersSpanish() {
        return switch (stateCode) {
            case "CA", "TX", "FL", "NM", "AZ", "NV", "NY", "NJ", "IL", "CO" -> true;
            default -> false;
        };
    }

    private boolean printedDisclosureRequired() {
        return switch (stateCode) {
            case "CA", "NY", "IL", "MA", "CT" -> true;
            default -> false;
        };
    }

    private String stateAuthorityName() {
        return switch (stateCode) {
            case "NY" -> "New York Department of Financial Services";
            case "CA" -> "California Department of Financial Protection and Innovation";
            case "TX" -> "Texas Department of Banking";
            case "FL" -> "Florida Office of Financial Regulation";
            case "IL" -> "Illinois Department of Financial and Professional Regulation";
            default -> stateName + " Banking Department";
        };
    }
}
