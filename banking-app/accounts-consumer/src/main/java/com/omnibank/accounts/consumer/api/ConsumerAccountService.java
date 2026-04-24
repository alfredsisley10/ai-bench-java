package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.AccountNumber;

public interface ConsumerAccountService {

    AccountNumber open(AccountOpening.Request request);

    BalanceView balance(AccountNumber account);

    void freeze(AccountNumber account, String reason);

    void unfreeze(AccountNumber account);

    void close(AccountNumber account, String reason);

    AccountStatus status(AccountNumber account);
}
