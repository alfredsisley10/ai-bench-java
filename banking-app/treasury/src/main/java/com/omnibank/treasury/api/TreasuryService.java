package com.omnibank.treasury.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;

public interface TreasuryService {

    ExchangeRate currentRate(CurrencyCode base, CurrencyCode quote);

    Money convert(Money source, CurrencyCode quote);

    void executeSweep(AccountNumber operating, AccountNumber investment, Money minimumOperatingBalance);
}
