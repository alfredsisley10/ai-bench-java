package com.omnibank.brand.rothira.omnidirect;

import java.util.List;

/**
 * Brand metadata and policy profile for RothIra on OmniDirect.
 */
public final class RothIraOmnidirectBrandProfile {

    private final String brandCode = "OMNIDIRECT";
    private final String displayName = "OmniDirect";
    private final String tagline = "Pure digital banking without the branch";
    private final String primarySegment = "DIGITAL_NATIVE";
    private final String primaryColor = "#00B894";
    private final boolean digitalOnly = true;
    private final int minimumAge = 18;
    private final boolean premiumBrand = false;
    private final boolean supportsForeignCurrency = false;
    private final int brandFounded = 2015;

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
