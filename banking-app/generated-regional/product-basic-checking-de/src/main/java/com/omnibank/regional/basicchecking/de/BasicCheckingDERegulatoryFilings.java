package com.omnibank.regional.basicchecking.de;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * State regulatory filing cadence for BasicChecking in Delaware.
 * Filings are separate from federal FFIEC reports and vary by
 * state. This class produces the filing calendar and metadata
 * needed for the batch-processing module to enqueue them.
 */
public final class BasicCheckingDERegulatoryFilings {

    public record Filing(
            String code,
            String label,
            String cadence,
            LocalDate nextDueDate,
            String filingAuthority,
            boolean electronic
    ) {}

    private final BasicCheckingDEProfile profile;

    public BasicCheckingDERegulatoryFilings() {
        this(new BasicCheckingDEProfile());
    }

    public BasicCheckingDERegulatoryFilings(BasicCheckingDEProfile profile) {
        this.profile = profile;
    }

    public List<Filing> quarterlyFilings(YearMonth quarter) {
        List<Filing> filings = new ArrayList<>();
        LocalDate dueDate = quarter.atEndOfMonth().plusDays(30);

        filings.add(new Filing(
                "STATE_CONDITION_REPORT",
                "Delaware Condition Report",
                "QUARTERLY",
                dueDate,
                profile.stateBankingAuthority(),
                true
        ));

        filings.add(new Filing(
                "UNCLAIMED_PROPERTY",
                "Unclaimed Property Pre-Notice (Delaware)",
                "ANNUAL",
                LocalDate.of(dueDate.getYear(), 10, 31),
                profile.stateBankingAuthority(),
                true
        ));

        if (profile.stateCode().equals("NY")) {
            filings.add(new Filing(
                    "NYS_23A_FILING",
                    "NYS Section 23 Quarterly Activity Report",
                    "QUARTERLY",
                    dueDate,
                    profile.stateBankingAuthority(),
                    true
            ));
        }
        if (profile.stateCode().equals("CA")) {
            filings.add(new Filing(
                    "CA_FI_FINANCIAL_CODE_332",
                    "California Financial Code §332 Report",
                    "QUARTERLY",
                    dueDate,
                    profile.stateBankingAuthority(),
                    true
            ));
        }
        if (profile.stateCode().equals("TX")) {
            filings.add(new Filing(
                    "TX_FIN_CODE_34_201",
                    "Texas Finance Code §34.201 Quarterly",
                    "QUARTERLY",
                    dueDate,
                    profile.stateBankingAuthority(),
                    true
            ));
        }

        return filings;
    }

    public List<Filing> annualFilings(int year) {
        List<Filing> filings = new ArrayList<>();
        filings.add(new Filing(
                "ANNUAL_COMPLIANCE_CERT",
                "Annual State Compliance Certification",
                "ANNUAL",
                LocalDate.of(year, 3, 31),
                profile.stateBankingAuthority(),
                true
        ));

        filings.add(new Filing(
                "STATE_ESCHEATMENT_REPORT",
                "Unclaimed Property Remittance (Delaware)",
                "ANNUAL",
                LocalDate.of(year, 11, 1),
                profile.stateBankingAuthority(),
                true
        ));

        return filings;
    }
}
