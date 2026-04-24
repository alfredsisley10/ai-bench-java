package com.omnibank.productvariants.cdladder6month;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Orchestrates the 6 Month CD product lifecycle — opening, funding,
 * accrual, fee assessment, and closure — backed by an in-memory
 * store. Production-equivalent wiring would replace the store with
 * JPA repositories; branching logic stays identical.
 */
public final class CdLadder6MonthService {

    public enum AccountState { PENDING, OPEN, DORMANT, MATURED, CLOSED }

    public record AccountSnapshot(
            UUID accountId,
            String customerReference,
            AccountState state,
            BigDecimal balance,
            BigDecimal accruedInterest,
            Instant openedAt,
            Instant lastActivityAt
    ) {}

    private final Clock clock;
    private final CdLadder6MonthProduct product;
    private final CdLadder6MonthFeeSchedule feeSchedule;
    private final CdLadder6MonthEligibilityRules eligibility;
    private final CdLadder6MonthPricingEngine pricing;
    private final ConcurrentHashMap<UUID, AccountSnapshot> accounts = new ConcurrentHashMap<>();
    private final List<Consumer<CdLadder6MonthLifecycleEvent>> subscribers = new ArrayList<>();

    public CdLadder6MonthService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.product = CdLadder6MonthProduct.defaults();
        this.feeSchedule = CdLadder6MonthFeeSchedule.defaults();
        this.eligibility = new CdLadder6MonthEligibilityRules();
        this.pricing = new CdLadder6MonthPricingEngine();
    }

    public void subscribe(Consumer<CdLadder6MonthLifecycleEvent> listener) {
        subscribers.add(Objects.requireNonNull(listener, "listener"));
    }

    public CdLadder6MonthProduct product() { return product; }
    public CdLadder6MonthFeeSchedule feeSchedule() { return feeSchedule; }
    public CdLadder6MonthEligibilityRules eligibility() { return eligibility; }
    public CdLadder6MonthPricingEngine pricing() { return pricing; }

    public Optional<AccountSnapshot> find(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public AccountSnapshot openAccount(CdLadder6MonthEligibilityRules.Applicant applicant,
                                        String customerReference, String channelId) {
        var assessment = eligibility.evaluate(applicant, LocalDate.now(clock));
        if (!assessment.isEligible()) {
            throw new IllegalStateException("Applicant not eligible: "
                    + assessment.findings());
        }

        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        AccountSnapshot snap = new AccountSnapshot(
                accountId, customerReference,
                AccountState.PENDING, BigDecimal.ZERO, BigDecimal.ZERO,
                now, now);
        accounts.put(accountId, snap);
        publish(new CdLadder6MonthLifecycleEvent.Opened(
                UUID.randomUUID(), now, product.productId(),
                customerReference, channelId));
        return snap;
    }

    public AccountSnapshot fund(UUID accountId, BigDecimal amount, String source) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("fund amount must be positive");
        }
        AccountSnapshot prior = requireAccount(accountId);
        AccountState nextState = prior.state() == AccountState.PENDING
                ? AccountState.OPEN : prior.state();
        Instant now = Instant.now(clock);
        AccountSnapshot updated = new AccountSnapshot(
                accountId, prior.customerReference(), nextState,
                prior.balance().add(amount), prior.accruedInterest(),
                prior.openedAt(), now);
        accounts.put(accountId, updated);
        publish(new CdLadder6MonthLifecycleEvent.Funded(
                UUID.randomUUID(), now, product.productId(), amount, source));
        return updated;
    }

    public AccountSnapshot accrueInterest(UUID accountId, LocalDate asOf) {
        AccountSnapshot prior = requireAccount(accountId);
        if (prior.state() != AccountState.OPEN) return prior;
        var result = pricing.accrueDaily(prior.balance(), asOf, prior.accruedInterest());
        AccountSnapshot updated = new AccountSnapshot(
                accountId, prior.customerReference(), prior.state(),
                prior.balance(), result.runningTotal(),
                prior.openedAt(), Instant.now(clock));
        accounts.put(accountId, updated);
        publish(new CdLadder6MonthLifecycleEvent.InterestAccrued(
                UUID.randomUUID(), Instant.now(clock), product.productId(),
                result.dailyAmount(), result.runningTotal()));
        return updated;
    }

    public AccountSnapshot assessMaintenanceFee(UUID accountId, LocalDate cycleDate) {
        AccountSnapshot prior = requireAccount(accountId);
        if (prior.state() != AccountState.OPEN) return prior;

        BigDecimal fee = feeSchedule.monthlyMaintenance();
        if (fee.signum() <= 0) {
            return prior;
        }
        BigDecimal waiver = feeSchedule.waiverThreshold("MONTHLY_MAINTENANCE");
        if (prior.balance().compareTo(waiver) >= 0 && waiver.signum() > 0) {
            publish(new CdLadder6MonthLifecycleEvent.FeeWaived(
                    UUID.randomUUID(), Instant.now(clock), product.productId(),
                    "MONTHLY_MAINTENANCE", "Balance >= waiver threshold"));
            return prior;
        }

        BigDecimal newBalance = prior.balance().subtract(fee)
                .setScale(2, RoundingMode.HALF_EVEN);
        AccountSnapshot updated = new AccountSnapshot(
                accountId, prior.customerReference(), prior.state(),
                newBalance, prior.accruedInterest(),
                prior.openedAt(), Instant.now(clock));
        accounts.put(accountId, updated);
        publish(new CdLadder6MonthLifecycleEvent.FeeAssessed(
                UUID.randomUUID(), Instant.now(clock), product.productId(),
                "MONTHLY_MAINTENANCE", fee));
        return updated;
    }

    public AccountSnapshot mature(UUID accountId, String dispositionAction) {
        AccountSnapshot prior = requireAccount(accountId);
        if (!product.requiresEarlyWithdrawalPenalty()) {
            throw new IllegalStateException("Only term products mature");
        }
        Instant now = Instant.now(clock);
        AccountSnapshot updated = new AccountSnapshot(
                accountId, prior.customerReference(), AccountState.MATURED,
                prior.balance(), prior.accruedInterest(),
                prior.openedAt(), now);
        accounts.put(accountId, updated);
        publish(new CdLadder6MonthLifecycleEvent.Matured(
                UUID.randomUUID(), now, product.productId(),
                dispositionAction, prior.balance()));
        return updated;
    }

    public AccountSnapshot close(UUID accountId, String reason, String closedBy) {
        AccountSnapshot prior = requireAccount(accountId);
        Instant now = Instant.now(clock);
        AccountSnapshot updated = new AccountSnapshot(
                accountId, prior.customerReference(), AccountState.CLOSED,
                prior.balance(), prior.accruedInterest(),
                prior.openedAt(), now);
        accounts.put(accountId, updated);
        publish(new CdLadder6MonthLifecycleEvent.Closed(
                UUID.randomUUID(), now, product.productId(), reason, closedBy));
        return updated;
    }

    private AccountSnapshot requireAccount(UUID id) {
        var acct = accounts.get(id);
        if (acct == null) throw new IllegalArgumentException("Unknown account " + id);
        return acct;
    }

    private void publish(CdLadder6MonthLifecycleEvent event) {
        for (var s : subscribers) {
            try { s.accept(event); } catch (RuntimeException ignored) {}
        }
    }
}
