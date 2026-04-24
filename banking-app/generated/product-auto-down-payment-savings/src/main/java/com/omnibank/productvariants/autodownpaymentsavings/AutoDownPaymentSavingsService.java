package com.omnibank.productvariants.autodownpaymentsavings;

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
 * Orchestrates the Auto Down Payment Savings product lifecycle — opening, funding,
 * accrual, fee assessment, and closure — backed by an in-memory
 * store. Production-equivalent wiring would replace the store with
 * JPA repositories; branching logic stays identical.
 */
public final class AutoDownPaymentSavingsService {

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
    private final AutoDownPaymentSavingsProduct product;
    private final AutoDownPaymentSavingsFeeSchedule feeSchedule;
    private final AutoDownPaymentSavingsEligibilityRules eligibility;
    private final AutoDownPaymentSavingsPricingEngine pricing;
    private final ConcurrentHashMap<UUID, AccountSnapshot> accounts = new ConcurrentHashMap<>();
    private final List<Consumer<AutoDownPaymentSavingsLifecycleEvent>> subscribers = new ArrayList<>();

    public AutoDownPaymentSavingsService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.product = AutoDownPaymentSavingsProduct.defaults();
        this.feeSchedule = AutoDownPaymentSavingsFeeSchedule.defaults();
        this.eligibility = new AutoDownPaymentSavingsEligibilityRules();
        this.pricing = new AutoDownPaymentSavingsPricingEngine();
    }

    public void subscribe(Consumer<AutoDownPaymentSavingsLifecycleEvent> listener) {
        subscribers.add(Objects.requireNonNull(listener, "listener"));
    }

    public AutoDownPaymentSavingsProduct product() { return product; }
    public AutoDownPaymentSavingsFeeSchedule feeSchedule() { return feeSchedule; }
    public AutoDownPaymentSavingsEligibilityRules eligibility() { return eligibility; }
    public AutoDownPaymentSavingsPricingEngine pricing() { return pricing; }

    public Optional<AccountSnapshot> find(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public AccountSnapshot openAccount(AutoDownPaymentSavingsEligibilityRules.Applicant applicant,
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.Opened(
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.Funded(
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.InterestAccrued(
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
            publish(new AutoDownPaymentSavingsLifecycleEvent.FeeWaived(
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.FeeAssessed(
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.Matured(
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
        publish(new AutoDownPaymentSavingsLifecycleEvent.Closed(
                UUID.randomUUID(), now, product.productId(), reason, closedBy));
        return updated;
    }

    private AccountSnapshot requireAccount(UUID id) {
        var acct = accounts.get(id);
        if (acct == null) throw new IllegalArgumentException("Unknown account " + id);
        return acct;
    }

    private void publish(AutoDownPaymentSavingsLifecycleEvent event) {
        for (var s : subscribers) {
            try { s.accept(event); } catch (RuntimeException ignored) {}
        }
    }
}
