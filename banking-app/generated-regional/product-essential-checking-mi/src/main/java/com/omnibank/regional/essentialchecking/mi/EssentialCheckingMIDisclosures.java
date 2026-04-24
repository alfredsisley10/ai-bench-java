package com.omnibank.regional.essentialchecking.mi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * State-specific disclosure overlays for EssentialChecking in Michigan.
 * Layered on top of the national disclosure, these capture
 * items that only apply under MI law (UDAP, UIPA, unclaimed
 * property reporting, language access, electronic signature).
 */
public final class EssentialCheckingMIDisclosures {

    private final EssentialCheckingMIProfile profile;

    public EssentialCheckingMIDisclosures() {
        this.profile = new EssentialCheckingMIProfile();
    }

    public EssentialCheckingMIDisclosures(EssentialCheckingMIProfile profile) {
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
        return "Michigan Residents Disclosure — Effective "
                + LocalDate.of(2026, 1, 1)
                + ". This supplement is part of the EssentialChecking account agreement.";
    }

    public String regulatoryAuthorityClause() {
        return "This product is offered subject to the rules of "
                + profile.stateBankingAuthority()
                + " and federal banking regulations. Consumer complaints may be directed to "
                + profile.stateBankingAuthority() + ".";
    }

    public String unclaimedPropertyClause() {
        return "Michigan unclaimed property law requires abandoned funds to be "
                + "escheated to the state after "
                + profile.escheatmentDormancyYears()
                + " years of inactivity. Notice will be mailed prior to escheatment.";
    }

    public String electronicSignatureClause() {
        return "By consenting to electronic delivery, you agree that disclosures, "
                + "statements, and notices for this EssentialChecking account may be delivered "
                + "electronically. You may withdraw consent at any time.";
    }

    public String languageAccessClause() {
        return "This account disclosure is available in Spanish upon request. "
                + "Llame a nuestro centro de servicio para recibir una copia en español.";
    }

    public String printedCopyRequiredClause() {
        return "A printed copy of this disclosure will be provided at account opening "
                + "as required by Michigan law, in addition to any electronic copy delivered.";
    }

    public String noSalesTaxClause() {
        return "Michigan does not impose sales tax on bank service fees; "
                + "fees listed in the schedule are the total charged to you.";
    }

    public String renderFullSupplement() {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(70)).append("\n");
        sb.append("EssentialChecking — Michigan State Disclosure Supplement\n");
        sb.append("=".repeat(70)).append("\n\n");
        for (String item : stateSpecificAdditions()) {
            sb.append(item).append("\n\n");
        }
        return sb.toString();
    }
}
