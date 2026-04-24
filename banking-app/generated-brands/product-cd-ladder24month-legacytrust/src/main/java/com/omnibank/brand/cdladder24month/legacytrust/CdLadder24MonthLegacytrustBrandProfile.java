package com.omnibank.brand.cdladder24month.legacytrust;

import java.util.List;

/**
 * Brand metadata and policy profile for CdLadder24Month on Legacy Trust.
 */
public final class CdLadder24MonthLegacytrustBrandProfile {

    private final String brandCode = "LEGACYTRUST";
    private final String displayName = "Legacy Trust";
    private final String tagline = "Private wealth since 1907";
    private final String primarySegment = "HIGH_NET_WORTH";
    private final String primaryColor = "#2D3436";
    private final boolean digitalOnly = false;
    private final int minimumAge = 25;
    private final boolean premiumBrand = true;
    private final boolean supportsForeignCurrency = true;
    private final int brandFounded = 1907;

    public String brandCode() { return brandCode; }
    public String displayName() { return displayName; }
    public String tagline() { return tagline; }
    public String primarySegment() { return primarySegment; }
    public String primaryColor() { return primaryColor; }
    public boolean digitalOnly() { return digitalOnly; }
    public int minimumAge() { return minimumAge; }
    public boolean premiumBrand() { return premiumBrand; }
    public boolean supportsForeignCurrency() { return supportsForeignCurrency; }
    public int brandFounded() { return brandFounded; }

    public int ageInYears(int currentYear) {
        return currentYear - brandFounded;
    }

    public boolean isHeritageBrand(int currentYear) {
        return ageInYears(currentYear) >= 50;
    }

    public List<String> brandValueStatements() {
        return switch (brandCode) {
            case "OMNIBANK" -> List.of(
                    "Accessible banking for every household",
                    "Transparent fees, no surprises",
                    "Available 24/7 through every channel");
            case "OMNIDIRECT" -> List.of(
                    "Mobile-first, branch-never",
                    "Zero account-opening fees",
                    "Customer support in-app within 60 seconds");
            case "SILVERBANK" -> List.of(
                    "Service without scripts",
                    "Printed statements always free",
                    "Live phone support with zero hold times");
            case "STARTUPFI" -> List.of(
                    "Accounts in minutes, not days",
                    "Treasury-grade features for early-stage companies",
                    "Integrated with the tools founders actually use");
            case "LEGACYTRUST" -> List.of(
                    "Privacy and discretion",
                    "Generational wealth preservation",
                    "Bespoke service under a single relationship manager");
            case "FUTUREBANK" -> List.of(
                    "Banking for the next generation",
                    "Parental controls that grow with your child",
                    "Savings goals gamified");
            case "BLUEWATERBANK" -> List.of(
                    "Local decisions, local relationships",
                    "Community-first, always",
                    "Owned by the people we serve");
            default -> List.of("Customer-first banking");
        };
    }

    public String brandPromiseHeadline() {
        return displayName + " — " + tagline;
    }
}
