package com.omnibank.brand.standardsavings.futurebank;

import java.util.ArrayList;
import java.util.List;

/**
 * Brand-tailored onboarding flow for StandardSavings under FutureBank.
 * Step ordering and which steps are required differ by brand:
 * LegacyTrust adds an in-person advisor intro; OmniDirect skips
 * physical mail; FutureBank requires a parent account.
 */
public final class StandardSavingsFuturebankOnboardingFlow {

    public record Step(String code, String label, boolean required, int sequenceNumber) {}

    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        int seq = 0;
        steps.add(new Step("IDENTITY_CAPTURE", "Verify your identity", true, seq++));
        steps.add(new Step("ADDRESS_CONFIRM", "Confirm your address", true, seq++));
        steps.add(new Step("TERMS_ACCEPT", "Accept account agreement", true, seq++));

        if ("FUTUREBANK".equals("FUTUREBANK")) {
            steps.add(new Step("PARENT_GUARDIAN_VERIFY",
                    "Parent or guardian verification", true, seq++));
        }

        if ("LEGACYTRUST".equals("FUTUREBANK")) {
            steps.add(new Step("WEALTH_SOURCE_DISCLOSURE",
                    "Source-of-wealth disclosure (private client)", true, seq++));
            steps.add(new Step("RELATIONSHIP_MANAGER_INTRO",
                    "Meet your relationship manager", true, seq++));
        }

        if ("STARTUPFI".equals("FUTUREBANK")) {
            steps.add(new Step("BUSINESS_FORMATION_DOCS",
                    "Upload formation documents", true, seq++));
            steps.add(new Step("BENEFICIAL_OWNERS",
                    "Declare 25%+ beneficial owners", true, seq++));
        }

        steps.add(new Step("FUNDING_INTENT",
                "Choose initial funding method", true, seq++));

        if (!false) {
            steps.add(new Step("PHYSICAL_DELIVERY",
                    "Shipping address for cards and debit cards", true, seq++));
        } else {
            steps.add(new Step("VIRTUAL_CARD_ISSUANCE",
                    "Issue virtual card immediately", true, seq++));
        }

        if ("OMNIDIRECT".equals("FUTUREBANK")) {
            steps.add(new Step("APP_INSTALL_PROMPT",
                    "Install mobile app to continue", true, seq++));
        }

        steps.add(new Step("FINAL_REVIEW", "Review and confirm", true, seq++));
        return steps;
    }

    public int stepCount() {
        return steps().size();
    }

    public boolean hasStep(String code) {
        return steps().stream().anyMatch(s -> s.code().equals(code));
    }

    public long estimatedMinutes() {
        return switch ("FUTUREBANK") {
            case "OMNIDIRECT" -> 4;
            case "OMNIBANK" -> 8;
            case "LEGACYTRUST" -> 45;
            case "STARTUPFI" -> 15;
            case "SILVERBANK" -> 12;
            case "FUTUREBANK" -> 10;
            default -> 10;
        };
    }
}
