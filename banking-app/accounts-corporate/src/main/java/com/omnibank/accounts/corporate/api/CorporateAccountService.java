package com.omnibank.accounts.corporate.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

public interface CorporateAccountService {

    AccountNumber openDda(CustomerId corporate, CurrencyCode currency);

    void configureZba(AccountNumber child, AccountNumber header);

    void configureSweep(AccountNumber operating, AccountNumber investment, Money target);

    Money ledgerBalance(AccountNumber account);
}
