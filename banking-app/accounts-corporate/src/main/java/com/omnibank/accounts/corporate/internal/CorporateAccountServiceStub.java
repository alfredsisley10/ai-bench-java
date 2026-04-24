package com.omnibank.accounts.corporate.internal;

import com.omnibank.accounts.corporate.api.CorporateAccountService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.springframework.stereotype.Service;

@Service
class CorporateAccountServiceStub implements CorporateAccountService {

    @Override
    public AccountNumber openDda(CustomerId corporate, CurrencyCode currency) {
        throw new UnsupportedOperationException("TODO: implement when a bug targets this module");
    }

    @Override public void configureZba(AccountNumber child, AccountNumber header) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override public void configureSweep(AccountNumber operating, AccountNumber investment, Money target) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override public Money ledgerBalance(AccountNumber account) {
        throw new UnsupportedOperationException("TODO");
    }
}
