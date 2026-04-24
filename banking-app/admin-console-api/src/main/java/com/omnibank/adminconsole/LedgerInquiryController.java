package com.omnibank.adminconsole;

import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.LedgerQueries;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/admin/api/v1/ledger")
public class LedgerInquiryController {

    private final LedgerQueries queries;

    public LedgerInquiryController(LedgerQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/trial-balance")
    public TrialBalance trialBalance(@RequestParam(required = false) LocalDate asOf) {
        return queries.trialBalance(asOf != null ? asOf : LocalDate.now());
    }

    @GetMapping("/accounts/{code}/balance")
    public Money balance(@PathVariable String code) {
        return queries.currentBalance(new GlAccountCode(code));
    }
}
