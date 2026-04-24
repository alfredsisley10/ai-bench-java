package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.statements.internal.StatementDeliveryService.Channel;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryDestination;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryRecord;
import com.omnibank.statements.internal.StatementGenerator.Format;
import com.omnibank.statements.internal.StatementGenerator.GenerationRequest;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;
import com.omnibank.statements.internal.StatementPreferencesManager.Frequency;
import com.omnibank.statements.internal.StatementPreferencesManager.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the monthly (or quarterly / annual) statement cycle.
 *
 * <p>On each cycle run the scheduler:
 * <ol>
 *   <li>Enumerates accounts whose cadence includes the current cycle-end date</li>
 *   <li>Resolves the customer's delivery preferences</li>
 *   <li>Generates statement content, renders it, and archives the artifact</li>
 *   <li>Dispatches delivery on the primary channel, falling back to the
 *       secondary channel on transport failure</li>
 *   <li>Captures per-account {@link CycleOutcome} rows the batch system reads
 *       for observability</li>
 * </ol>
 *
 * <p>Weekend / holiday handling: the effective "statement date" for a cycle
 * is rolled to the prior business day if the nominal date falls on a
 * weekend or registered holiday — Omnibank avoids Sunday-dated statements
 * to stay aligned with Fed settlement days.
 */
public class StatementCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatementCycleScheduler.class);

    /** Source of ready-to-cycle accounts — injected so tests plug in a list. */
    public interface AccountEnumerator {
        /** Accounts whose cycle-end equals {@code cycleEnd} at the target frequency. */
        List<AccountCycleSpec> dueOn(LocalDate cycleEnd, Frequency frequency);
    }

    /** Source of per-account cycle data (request, destination, customer id). */
    public interface CycleDataProvider {
        /** The statement request needed to feed {@link StatementGenerator#generate}. */
        GenerationRequest requestFor(AccountNumber account, LocalDate cycleStart, LocalDate cycleEnd);

        /** Destination override — null to let scheduler derive from preferences. */
        DeliveryDestination destinationFor(AccountNumber account, Channel channel);
    }

    /** Spec identifying a single account to be cycled. */
    public record AccountCycleSpec(
            AccountNumber account,
            CustomerId customer,
            LocalDate cycleStart,
            LocalDate cycleEnd
    ) {
        public AccountCycleSpec {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(cycleStart, "cycleStart");
            Objects.requireNonNull(cycleEnd, "cycleEnd");
        }
    }

    /** Result status of processing one account's cycle. */
    public enum OutcomeStatus {
        GENERATED_AND_DELIVERED,
        GENERATED_PENDING_RETRY,
        GENERATED_DELIVERY_FAILED,
        SKIPPED_NO_PREFERENCES,
        SKIPPED_NO_DESTINATION,
        FAILED
    }

    /** Per-account summary row. */
    public record CycleOutcome(
            AccountNumber account,
            CustomerId customer,
            String statementId,
            OutcomeStatus status,
            String deliveryId,
            String message
    ) {
        public CycleOutcome {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(status, "status");
        }
    }

    /** Holidays the scheduler treats as non-business days (federal + bank-specific). */
    private final Set<LocalDate> holidays;

    private final Clock clock;
    private final StatementGenerator generator;
    private final StatementArchiveService archive;
    private final StatementDeliveryService delivery;
    private final StatementPreferencesManager preferences;
    private final AccountEnumerator enumerator;
    private final CycleDataProvider dataProvider;
    private final Map<LocalDate, List<CycleOutcome>> history = new ConcurrentHashMap<>();

    public StatementCycleScheduler(Clock clock,
                                   StatementGenerator generator,
                                   StatementArchiveService archive,
                                   StatementDeliveryService delivery,
                                   StatementPreferencesManager preferences,
                                   AccountEnumerator enumerator,
                                   CycleDataProvider dataProvider,
                                   Set<LocalDate> holidays) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator");
        this.dataProvider = Objects.requireNonNull(dataProvider, "dataProvider");
        this.holidays = Set.copyOf(holidays == null ? Set.of() : holidays);
    }

    /** Run one cycle. Returns the per-account outcomes in enumeration order. */
    public List<CycleOutcome> runCycle(LocalDate nominalCycleEnd, Frequency frequency) {
        Objects.requireNonNull(nominalCycleEnd, "nominalCycleEnd");
        Objects.requireNonNull(frequency, "frequency");

        LocalDate effectiveEnd = adjustForWeekendOrHoliday(nominalCycleEnd);
        if (!effectiveEnd.equals(nominalCycleEnd)) {
            log.info("Cycle date adjusted: nominal={} effective={} (weekend/holiday)",
                    nominalCycleEnd, effectiveEnd);
        }

        List<AccountCycleSpec> due = enumerator.dueOn(effectiveEnd, frequency);
        log.info("Running statement cycle for {} accounts on {} (freq={})",
                due.size(), effectiveEnd, frequency);

        List<CycleOutcome> results = new ArrayList<>(due.size());
        for (AccountCycleSpec spec : due) {
            results.add(processOne(spec));
        }
        history.put(effectiveEnd, List.copyOf(results));
        return results;
    }

    /** Look up the outcomes from a prior cycle run by effective date. */
    public List<CycleOutcome> historyFor(LocalDate effectiveCycleEnd) {
        return history.getOrDefault(effectiveCycleEnd, List.of());
    }

    /** True if {@code date} is a bank business day (no weekend, no holiday). */
    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /** Expose holiday calendar (defensive copy) for downstream callers. */
    public Set<LocalDate> holidays() {
        return Collections.unmodifiableSet(holidays);
    }

    /**
     * Roll a date backward to the closest prior business day. Public so calling
     * code that wants to pre-compute cycle dates can share the rule.
     */
    public LocalDate adjustForWeekendOrHoliday(LocalDate date) {
        LocalDate cursor = date;
        while (!isBusinessDay(cursor)) {
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    // ── internals ────────────────────────────────────────────────────────

    private CycleOutcome processOne(AccountCycleSpec spec) {
        Preferences prefs;
        try {
            prefs = preferences.get(spec.customer());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping account {} — no preferences for customer {}",
                    spec.account(), spec.customer());
            return new CycleOutcome(spec.account(), spec.customer(), null,
                    OutcomeStatus.SKIPPED_NO_PREFERENCES, null, e.getMessage());
        }

        Channel primaryChannel = prefs.primaryChannel();
        Format format = defaultFormatFor(primaryChannel);

        try {
            GenerationRequest request = dataProvider.requestFor(
                    spec.account(), spec.cycleStart(), spec.cycleEnd());
            RenderedStatement rendered = generator.generateAndRender(request, format);
            archive.archive(rendered);

            DeliveryDestination destination = dataProvider.destinationFor(spec.account(), primaryChannel);
            if (destination == null) {
                return new CycleOutcome(spec.account(), spec.customer(),
                        rendered.content().statementId(),
                        OutcomeStatus.SKIPPED_NO_DESTINATION, null,
                        "Missing destination for primary channel " + primaryChannel);
            }

            DeliveryRecord record = delivery.deliver(rendered, spec.customer(), destination);

            if (record.status() == StatementDeliveryService.DeliveryStatus.DELIVERED) {
                return new CycleOutcome(spec.account(), spec.customer(),
                        rendered.content().statementId(),
                        OutcomeStatus.GENERATED_AND_DELIVERED, record.deliveryId(), null);
            }
            if (record.status() == StatementDeliveryService.DeliveryStatus.PENDING) {
                // Try fallback synchronously if one is configured.
                DeliveryRecord maybeFallback = tryFallback(prefs, spec, rendered, record);
                if (maybeFallback != null
                        && maybeFallback.status() == StatementDeliveryService.DeliveryStatus.DELIVERED) {
                    return new CycleOutcome(spec.account(), spec.customer(),
                            rendered.content().statementId(),
                            OutcomeStatus.GENERATED_AND_DELIVERED,
                            maybeFallback.deliveryId(), "Delivered via fallback channel");
                }
                return new CycleOutcome(spec.account(), spec.customer(),
                        rendered.content().statementId(),
                        OutcomeStatus.GENERATED_PENDING_RETRY, record.deliveryId(),
                        record.lastError());
            }
            return new CycleOutcome(spec.account(), spec.customer(),
                    rendered.content().statementId(),
                    OutcomeStatus.GENERATED_DELIVERY_FAILED, record.deliveryId(),
                    record.lastError());
        } catch (RuntimeException ex) {
            log.error("Cycle processing failed for account {}", spec.account(), ex);
            return new CycleOutcome(spec.account(), spec.customer(), null,
                    OutcomeStatus.FAILED, null, ex.getMessage());
        }
    }

    private DeliveryRecord tryFallback(Preferences prefs,
                                       AccountCycleSpec spec,
                                       RenderedStatement rendered,
                                       DeliveryRecord primaryResult) {
        return prefs.fallback().map(fallbackChannel -> {
            DeliveryDestination dest = dataProvider.destinationFor(spec.account(), fallbackChannel);
            if (dest == null) {
                return primaryResult;
            }
            Format required = defaultFormatFor(fallbackChannel);
            RenderedStatement fallbackRendered = rendered.format() == required
                    ? rendered
                    : generator.render(rendered.content(), required);
            log.info("Routing statement {} to fallback channel {}",
                    rendered.content().statementId(), fallbackChannel);
            return delivery.deliver(fallbackRendered, spec.customer(), dest);
        }).orElse(null);
    }

    private static Format defaultFormatFor(Channel channel) {
        return switch (channel) {
            case E_STATEMENT_SFTP, USPS_PHYSICAL_MAIL, THIRD_PARTY_AGGREGATOR -> Format.PDF;
            case SECURE_MESSAGE_PORTAL -> Format.HTML;
        };
    }

    /** Clock accessor for external audit trails that want to stamp run times consistently. */
    public Clock clock() {
        return clock;
    }
}
