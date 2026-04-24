package com.omnibank.fraud.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;

public interface FraudService {

    FraudDecision evaluate(AccountNumber account, Money amount, String counterparty, String channel);
}
