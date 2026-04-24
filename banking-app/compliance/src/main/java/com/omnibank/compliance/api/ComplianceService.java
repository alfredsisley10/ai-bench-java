package com.omnibank.compliance.api;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

public interface ComplianceService {

    KycStatus performKyc(CustomerId customer);

    SanctionsScreeningResult screenAgainstOfac(String name, String country);

    void fileCtrIfRequired(CustomerId customer, Money cashAmount);

    void fileSar(CustomerId customer, String narrative);
}
