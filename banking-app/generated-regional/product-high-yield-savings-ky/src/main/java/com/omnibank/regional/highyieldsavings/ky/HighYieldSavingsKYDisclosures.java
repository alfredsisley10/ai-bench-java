package com.omnibank.regional.highyieldsavings.ky;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * State-specific disclosure overlays for HighYieldSavings in Kentucky.
 * Layered on top of the national disclosure, these capture
 * items that only apply under KY law (UDAP, UIPA, unclaimed
 * property reporting, language access, electronic signature).
 */
public final class HighYieldSavingsKYDisclosures {

    private final HighYieldSavingsKYProfile profile;

    public HighYieldSavingsKYDisclosures() {
        this.profile = new HighYieldSavingsKYProfile();
    }

    public HighYieldSavingsKYDisclosures(HighYieldSavingsKYProfile profile) {
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
        return "Kentucky Residents Disclosure — Effective "
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
        return "Kentucky unclaimed property law requires abandoned funds to be "
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
                + "as required by Kentucky law, in addition to any electronic copy delivered.";
    }

    public String noSalesTaxClause() {
        return "Kentucky does not impose sales tax on bank service fees; "
                + "fees listed in the schedule are the total charged to you.";
    }

    public String renderFullSupplement() {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(70)).append("\n");
        sb.append("HighYieldSavings — Kentucky State Disclosure Supplement\n");
        sb.append("=".repeat(70)).append("\n\n");
        for (String item : stateSpecificAdditions()) {
            sb.append(item).append("\n\n");
        }
        return sb.toString();
    }
}
