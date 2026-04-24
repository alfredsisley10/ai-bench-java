package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.ledger.api.GlAccountCode;

/**
 * Maps consumer deposit accounts to the GL accounts they post into.
 * Customer deposit is a bank liability, so these are all LIA codes.
 */
final class LedgerMapping {

    // Checking account product family → GL code
    static final GlAccountCode CHECKING_LIABILITY = new GlAccountCode("LIA-2100-001");
    static final GlAccountCode SAVINGS_LIABILITY  = new GlAccountCode("LIA-2110-001");
    static final GlAccountCode CD_LIABILITY       = new GlAccountCode("LIA-2120-001");
    static final GlAccountCode CASH_AT_FED        = new GlAccountCode("ASS-1100-001");
    static final GlAccountCode INTEREST_EXPENSE   = new GlAccountCode("EXP-7100-001");

    static GlAccountCode depositLiability(ConsumerProduct product) {
        return switch (product.kind) {
            case CHECKING -> CHECKING_LIABILITY;
            case SAVINGS -> SAVINGS_LIABILITY;
            case CD -> CD_LIABILITY;
        };
    }

    private LedgerMapping() {}
}
