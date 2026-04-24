package com.omnibank.productvariants.coverdellesa;

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
 * Orchestrates the Coverdell Education Savings product lifecycle — opening, funding,
 * accrual, fee assessment, and closure — backed by an in-memory
 * store. Production-equivalent wiring would replace the store with
 * JPA repositories; branching logic stays identical.
 */
public final class CoverdellEsaService {

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
    private final CoverdellEsaProduct product;
    private final CoverdellEsaFeeSchedule feeSchedule;
    private final CoverdellEsaEligibilityRules eligibility;
    private final CoverdellEsaPricingEngine pricing;
    private final ConcurrentHashMap<UUID, AccountSnapshot> accounts = new ConcurrentHashMap<>();
    private final List<Consumer<CoverdellEsaLifecycleEvent>> subscribers = new ArrayList<>();

    public CoverdellEsaService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.product = CoverdellEsaProduct.defaults();
        this.feeSchedule = CoverdellEsaFeeSchedule.defaults();
        this.eligibility = new CoverdellEsaEligibilityRules();
        this.pricing = new CoverdellEsaPricingEngine();
    }

    public void subscribe(Consumer<CoverdellEsaLifecycleEvent> listener) {
        subscribers.add(Objects.requireNonNull(listener, "listener"));
    }

    public CoverdellEsaProduct product() { return product; }
    public CoverdellEsaFeeSchedule feeSchedule() { return feeSchedule; }
    public CoverdellEsaEligibilityRules eligibility() { return eligibility; }
    public CoverdellEsaPricingEngine pricing() { return pricing; }

    public Optional<AccountSnapshot> find(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public AccountSnapshot openAccount(CoverdellEsaEligibilityRules.Applicant applicant,
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
        publish(new CoverdellEsaLifecycleEvent.Opened(
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
        publish(new CoverdellEsaLifecycleEvent.Funded(
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
        publish(new CoverdellEsaLifecycleEvent.InterestAccrued(
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
            publish(new CoverdellEsaLifecycleEvent.FeeWaived(
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
        publish(new CoverdellEsaLifecycleEvent.FeeAssessed(
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
        publish(new CoverdellEsaLifecycleEvent.Matured(
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
        publish(new CoverdellEsaLifecycleEvent.Closed(
                UUID.randomUUID(), now, product.productId(), reason, closedBy));
        return updated;
    }

    private AccountSnapshot requireAccount(UUID id) {
        var acct = accounts.get(id);
        if (acct == null) throw new IllegalArgumentException("Unknown account " + id);
        return acct;
    }

    private void publish(CoverdellEsaLifecycleEvent event) {
        for (var s : subscribers) {
            try { s.accept(event); } catch (RuntimeException ignored) {}
        }
    }
}
