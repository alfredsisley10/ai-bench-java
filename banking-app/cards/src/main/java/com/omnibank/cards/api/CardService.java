package com.omnibank.cards.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.util.UUID;

public interface CardService {

    UUID issue(CustomerId holder, AccountNumber linkedAccount, CardProduct product);

    AuthorizationDecision authorize(UUID card, Money amount, String merchantCategoryCode);

    void block(UUID card, String reason);

    void reportLostOrStolen(UUID card);
}
