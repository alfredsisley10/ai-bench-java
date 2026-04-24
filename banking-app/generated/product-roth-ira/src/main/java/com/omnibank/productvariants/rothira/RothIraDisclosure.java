package com.omnibank.productvariants.rothira;

import java.time.LocalDate;
import java.util.List;

/**
 * Regulation-mandated disclosure text for the Roth IRA product.
 * In the US, this is the TISA/Truth-in-Savings disclosure content
 * plus Reg DD required language. Rendered for customer consumption
 * at account opening and available on-demand thereafter.
 */
public final class RothIraDisclosure {

    public String title() {
        return "Roth IRA — Truth in Savings Disclosure";
    }

    public String effectiveDateText(LocalDate effective) {
        return "This disclosure is effective " + effective + " and supersedes any prior version.";
    }

    public List<String> keyTerms() {
        return List.of(
                "Annual Percentage Yield (APY): see rate sheet for current APY based on daily balance",
                "Minimum balance to open the account: $0.00",
                "Minimum daily balance to avoid fee: see fee schedule",
                "Monthly maintenance fee: $0.00 (may be waived based on balance)",
                "Compounding and crediting: interest compounds daily and credits monthly",
                "Fees for withdrawals exceeding federal transfer limits may apply"
        );
    }

    public List<String> regulatoryDisclosures() {
        return List.of(
                "FDIC insured to the maximum amount allowed by law for this category.",
                "Member FDIC / Equal Housing Lender.",
                "Federal law requires all financial institutions to obtain, verify, and record "
                        + "information that identifies each person who opens an account (USA PATRIOT Act).",
                "Rates may change at the bank's discretion; customer will be notified of material changes."
        );
    }

    public List<String> earlyWithdrawalPenalty() {
        if (0 <= 0) {
            return List.of("No early withdrawal penalty applies to the Roth IRA.");
        }
        return List.of(
                "An early withdrawal penalty applies to the Roth IRA.",
                "Penalty equals interest on the amount withdrawn for 0 days.",
                "Penalty may reduce principal; partial withdrawals are not permitted "
                        + "unless specifically authorized in writing."
        );
    }

    public List<String> feeSummary() {
        return List.of(
                "Monthly maintenance: $0.00",
                "Waived with qualifying daily balance of $0.00 or more",
                "Paper statement fee: $2.00/month (waived with e-statements)",
                "Overdraft item: $35.00 per paid item (max 6 per day)",
                "Stop payment: $30.00 per request",
                "Wire transfer (domestic out): $25.00 per transaction",
                "Wire transfer (international out): $45.00 per transaction"
        );
    }

    public String renderFullDisclosure(LocalDate effective) {
        StringBuilder sb = new StringBuilder();
        sb.append(title()).append("\n");
        sb.append("=".repeat(title().length())).append("\n\n");
        sb.append(effectiveDateText(effective)).append("\n\n");

        sb.append("KEY TERMS\n");
        for (String term : keyTerms()) sb.append(" - ").append(term).append("\n");
        sb.append("\n");

        sb.append("FEES\n");
        for (String fee : feeSummary()) sb.append(" - ").append(fee).append("\n");
        sb.append("\n");

        sb.append("EARLY WITHDRAWAL\n");
        for (String p : earlyWithdrawalPenalty()) sb.append(" - ").append(p).append("\n");
        sb.append("\n");

        sb.append("REGULATORY DISCLOSURES\n");
        for (String d : regulatoryDisclosures()) sb.append(" - ").append(d).append("\n");
        return sb.toString();
    }
}
