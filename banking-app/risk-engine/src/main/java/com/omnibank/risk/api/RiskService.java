package com.omnibank.risk.api;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

public interface RiskService {

    CreditScore score(CustomerId customer);

    Money availableCommitment(CustomerId customer);

    boolean withinLimits(CustomerId customer, Money proposedExposure);
}
