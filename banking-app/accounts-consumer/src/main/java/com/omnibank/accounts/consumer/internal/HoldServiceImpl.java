package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.HoldService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class HoldServiceImpl implements HoldService {

    private final HoldRepository holds;
    private final Clock clock;

    public HoldServiceImpl(HoldRepository holds, Clock clock) {
        this.holds = holds;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID placeHold(AccountNumber account, Money amount, String reason, Duration ttl) {
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("Hold amount must be positive: " + amount);
        }
        UUID id = UUID.randomUUID();
        Instant now = Timestamp.now(clock);
        HoldEntity entity = new HoldEntity(id, account.raw(), amount.amount(), amount.currency(),
                reason, now, now.plus(ttl));
        holds.save(entity);
        return id;
    }

    @Override
    @Transactional
    public void releaseHold(UUID holdId) {
        HoldEntity h = holds.findById(holdId).orElseThrow(() ->
                new IllegalArgumentException("Unknown hold: " + holdId));
        if (h.releasedAt() == null) {
            h.release(Timestamp.now(clock));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldView> activeHolds(AccountNumber account) {
        Instant now = Timestamp.now(clock);
        List<HoldView> out = new ArrayList<>();
        for (HoldEntity h : holds.findByAccountNumberAndReleasedAtIsNull(account.raw())) {
            if (h.isActive(now)) {
                out.add(new HoldView(h.id(), account,
                        Money.of(h.amount(), h.currency()),
                        h.reason(), h.placedAt(), h.expiresAt()));
            }
        }
        return out;
    }
}
