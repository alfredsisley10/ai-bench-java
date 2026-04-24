package com.omnibank.regional.highyieldsavings.ne;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * State-specific disclosure overlays for HighYieldSavings in Nebraska.
 * Layered on top of the national disclosure, these capture
 * items that only apply under NE law (UDAP, UIPA, unclaimed
 * property reporting, language access, electronic signature).
 */
public final class HighYieldSavingsNEDisclosures {

    private final HighYieldSavingsNEProfile profile;

    public HighYieldSavingsNEDisclosures() {
        this.profile = new HighYieldSavingsNEProfile();
    }

    public HighYieldSavingsNEDisclosures(HighYieldSavingsNEProfile profile) {
        this.profile = profile;
    }

    public List<String> stateSpecificAdditions() {
        List<String> items = new ArrayList<>();
        items.add(stateHeader());
        items.add(regulatoryAuthorityClause());
        items.add(unclaimedPropertyClause());
        items.add(electronicSignatureClause());
        if (profile.offersSpanishLanguageSupport()) {
            items.add(languageAccessClause());
        }
        if (profile.requiresPrintedDisclosure()) {
            items.add(printedCopyRequiredClause());
        }
        if (profile.isNoSalesTaxState()) {
            items.add(noSalesTaxClause());
        }
        return items;
    }

    public String stateHeader() {
        return "Nebraska Residents Disclosure — Effective "
                + LocalDate.of(2026, 1, 1)
                + ". This supplement is part of the HighYieldSavings account agreement.";
    }

    public String regulatoryAuthorityClause() {
        return "This product is offered subject to the rules of "
                + profile.stateBankingAuthority()
                + " and federal banking regulations. Consumer complaints may be directed to "
                + profile.stateBankingAuthority() + ".";
    }

    public String unclaimedPropertyClause() {
        return "Nebraska unclaimed property law requires abandoned funds to be "
                + "escheated to the state after "
                + profile.escheatmentDormancyYears()
                + " years of inactivity. Notice will be mailed prior to escheatment.";
    }

    public String electronicSignatureClause() {
        return "By consenting to electronic delivery, you agree that disclosures, "
                + "statements, and notices for this HighYieldSavings account may be delivered "
                + "electronically. You may withdraw consent at any time.";
    }

    public String languageAccessClause() {
        return "This account disclosure is available in Spanish upon request. "
                + "Llame a nuestro centro de servicio para recibir una copia en español.";
    }

    public String printedCopyRequiredClause() {
        return "A printed copy of this disclosure will be provided at account opening "
                + "as required by Nebraska law, in addition to any electronic copy delivered.";
    }

    public String noSalesTaxClause() {
        return "Nebraska does not impose sales tax on bank service fees; "
                + "fees listed in the schedule are the total charged to you.";
    }

    public String renderFullSupplement() {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(70)).append("\n");
        sb.append("HighYieldSavings — Nebraska State Disclosure Supplement\n");
        sb.append("=".repeat(70)).append("\n\n");
        for (String item : stateSpecificAdditions()) {
            sb.append(item).append("\n\n");
        }
        return sb.toString();
    }
}
