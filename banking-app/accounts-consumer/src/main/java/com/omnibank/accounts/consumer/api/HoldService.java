package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface HoldService {

    UUID placeHold(AccountNumber account, Money amount, String reason, Duration ttl);

    void releaseHold(UUID holdId);

    List<HoldView> activeHolds(AccountNumber account);

    record HoldView(UUID id, AccountNumber account, Money amount, String reason, java.time.Instant placedAt, java.time.Instant expiresAt) {}
}
