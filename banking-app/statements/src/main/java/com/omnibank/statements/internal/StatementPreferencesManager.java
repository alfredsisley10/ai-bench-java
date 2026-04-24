package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.statements.internal.StatementDeliveryService.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-customer statement delivery preferences.
 *
 * <p>Preference surface area is intentionally broad because the regulator, the
 * product marketing team, and customer service all have different levers:
 * <ul>
 *   <li>Paperless enrollment (opt-in to electronic only)</li>
 *   <li>Primary delivery channel + fallback</li>
 *   <li>Delivery address override (hotel, seasonal, P.O. box)</li>
 *   <li>Language (English / Spanish) — TILA § 1026.2 bilingual mandate</li>
 *   <li>Combined statement groups (multiple account numbers in one envelope)</li>
 *   <li>Frequency (MONTHLY, QUARTERLY, ANNUAL — ANNUAL only legal for
 *       certain low-balance savings products)</li>
 * </ul>
 *
 * <p>Changes are audit-logged — each preference mutation stores a
 * {@link PreferenceChangeEvent} so the change history is queryable for
 * compliance or fraud review without touching an external audit service.
 */
public class StatementPreferencesManager {

    private static final Logger log = LoggerFactory.getLogger(StatementPreferencesManager.class);

    /** Statement cadence a customer may elect. */
    public enum Frequency {
        MONTHLY,
        QUARTERLY,
        ANNUAL
    }

    /** Supported languages. */
    public enum Language {
        EN,
        ES
    }

    /** Full preference set for one customer. */
    public record Preferences(
            CustomerId customer,
            boolean paperless,
            Channel primaryChannel,
            Channel fallbackChannel,
            String addressOverride,
            Language language,
            Frequency frequency,
            Set<AccountNumber> combinedGroup,
            Instant lastUpdatedAt
    ) {
        public Preferences {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(primaryChannel, "primaryChannel");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(combinedGroup, "combinedGroup");
            Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
            combinedGroup = Collections.unmodifiableSet(new LinkedHashSet<>(combinedGroup));
        }

        public Optional<Channel> fallback() {
            return Optional.ofNullable(fallbackChannel);
        }

        public Optional<String> address() {
            return Optional.ofNullable(addressOverride).filter(a -> !a.isBlank());
        }
    }

    /** Audit entry produced by every preference mutation. */
    public record PreferenceChangeEvent(
            CustomerId customer,
            String field,
            String previousValue,
            String newValue,
            String actor,
            Instant changedAt
    ) {
        public PreferenceChangeEvent {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    private static final String DEFAULT_ACTOR_SELF = "CUSTOMER_SELF_SERVICE";

    private final Clock clock;
    private final java.util.Map<CustomerId, Preferences> store = new ConcurrentHashMap<>();
    private final java.util.Map<CustomerId, List<PreferenceChangeEvent>> audit = new ConcurrentHashMap<>();

    public StatementPreferencesManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Enroll a new customer with sensible defaults (paper MAIL, monthly, EN). */
    public Preferences enrollDefault(CustomerId customer) {
        Objects.requireNonNull(customer, "customer");
        if (store.containsKey(customer)) {
            return store.get(customer);
        }
        Preferences defaults = new Preferences(
                customer,
                false,
                Channel.USPS_PHYSICAL_MAIL,
                Channel.SECURE_MESSAGE_PORTAL,
                null,
                Language.EN,
                Frequency.MONTHLY,
                Collections.emptySet(),
                clock.instant());
        store.put(customer, defaults);
        recordChange(customer, "enrollment", null, "default", "SYSTEM_ENROLLMENT");
        log.info("Enrolled customer {} with default preferences", customer);
        return defaults;
    }

    /** Fetch current preferences for a customer. */
    public Preferences get(CustomerId customer) {
        Objects.requireNonNull(customer, "customer");
        Preferences prefs = store.get(customer);
        if (prefs == null) {
            throw new IllegalArgumentException("No preferences for customer: " + customer);
        }
        return prefs;
    }

    /**
     * Enroll (or unenroll) a customer from paperless delivery. Paperless
     * enrollment also flips the primary channel to SECURE_MESSAGE_PORTAL, so
     * the mutation is atomic (no transient "paperless but still physical mail"
     * state visible to downstream systems).
     */
    public Preferences setPaperless(CustomerId customer, boolean paperless, String actor) {
        Preferences current = get(customer);
        if (current.paperless() == paperless) {
            return current;
        }
        Channel newPrimary = paperless
                ? Channel.SECURE_MESSAGE_PORTAL
                : Channel.USPS_PHYSICAL_MAIL;
        Preferences updated = withChanges(
                current,
                paperless,
                newPrimary,
                current.fallbackChannel(),
                current.addressOverride(),
                current.language(),
                current.frequency(),
                current.combinedGroup());
        store.put(customer, updated);
        recordChange(customer, "paperless", Boolean.toString(current.paperless()),
                Boolean.toString(paperless), orSelf(actor));
        return updated;
    }

    /**
     * Set the primary channel + optional fallback. Passing the same primary as
     * fallback is rejected so alerts don't silently fold back to the same path.
     */
    public Preferences setChannels(CustomerId customer, Channel primary, Channel fallback, String actor) {
        Objects.requireNonNull(primary, "primary");
        if (primary == fallback) {
            throw new IllegalArgumentException("Fallback channel must differ from primary");
        }
        Preferences current = get(customer);
        Preferences updated = withChanges(
                current,
                current.paperless(),
                primary,
                fallback,
                current.addressOverride(),
                current.language(),
                current.frequency(),
                current.combinedGroup());
        store.put(customer, updated);
        recordChange(customer, "primaryChannel",
                current.primaryChannel().name(), primary.name(), orSelf(actor));
        recordChange(customer, "fallbackChannel",
                current.fallbackChannel() == null ? null : current.fallbackChannel().name(),
                fallback == null ? null : fallback.name(),
                orSelf(actor));
        return updated;
    }

    /** Override the mailing address for statements only. Pass {@code null} to clear. */
    public Preferences setAddressOverride(CustomerId customer, String address, String actor) {
        Preferences current = get(customer);
        String normalized = (address == null || address.isBlank()) ? null : address.trim();
        Preferences updated = withChanges(
                current,
                current.paperless(),
                current.primaryChannel(),
                current.fallbackChannel(),
                normalized,
                current.language(),
                current.frequency(),
                current.combinedGroup());
        store.put(customer, updated);
        recordChange(customer, "addressOverride",
                current.addressOverride(), normalized, orSelf(actor));
        return updated;
    }

    /** Set statement language (EN/ES). */
    public Preferences setLanguage(CustomerId customer, Language language, String actor) {
        Objects.requireNonNull(language, "language");
        Preferences current = get(customer);
        if (current.language() == language) return current;
        Preferences updated = withChanges(
                current,
                current.paperless(),
                current.primaryChannel(),
                current.fallbackChannel(),
                current.addressOverride(),
                language,
                current.frequency(),
                current.combinedGroup());
        store.put(customer, updated);
        recordChange(customer, "language", current.language().name(), language.name(), orSelf(actor));
        return updated;
    }

    /** Change statement cadence. */
    public Preferences setFrequency(CustomerId customer, Frequency frequency, String actor) {
        Objects.requireNonNull(frequency, "frequency");
        Preferences current = get(customer);
        if (current.frequency() == frequency) return current;
        Preferences updated = withChanges(
                current,
                current.paperless(),
                current.primaryChannel(),
                current.fallbackChannel(),
                current.addressOverride(),
                current.language(),
                frequency,
                current.combinedGroup());
        store.put(customer, updated);
        recordChange(customer, "frequency",
                current.frequency().name(), frequency.name(), orSelf(actor));
        return updated;
    }

    /**
     * Add an account to the customer's combined-statement group. Combined
     * statements mail all grouped accounts in one envelope — reduces postage
     * for customers with multiple accounts at the same household.
     */
    public Preferences addToCombinedGroup(CustomerId customer, AccountNumber account, String actor) {
        Objects.requireNonNull(account, "account");
        Preferences current = get(customer);
        if (current.combinedGroup().contains(account)) return current;
        Set<AccountNumber> next = new LinkedHashSet<>(current.combinedGroup());
        next.add(account);
        Preferences updated = withChanges(
                current,
                current.paperless(),
                current.primaryChannel(),
                current.fallbackChannel(),
                current.addressOverride(),
                current.language(),
                current.frequency(),
                next);
        store.put(customer, updated);
        recordChange(customer, "combinedGroup.add", null, account.raw(), orSelf(actor));
        return updated;
    }

    /** Remove an account from the combined group. */
    public Preferences removeFromCombinedGroup(CustomerId customer, AccountNumber account, String actor) {
        Objects.requireNonNull(account, "account");
        Preferences current = get(customer);
        if (!current.combinedGroup().contains(account)) return current;
        Set<AccountNumber> next = new LinkedHashSet<>(current.combinedGroup());
        next.remove(account);
        Preferences updated = withChanges(
                current,
                current.paperless(),
                current.primaryChannel(),
                current.fallbackChannel(),
                current.addressOverride(),
                current.language(),
                current.frequency(),
                next);
        store.put(customer, updated);
        recordChange(customer, "combinedGroup.remove", account.raw(), null, orSelf(actor));
        return updated;
    }

    /** Full audit log for a customer, oldest first. */
    public List<PreferenceChangeEvent> auditLog(CustomerId customer) {
        Objects.requireNonNull(customer, "customer");
        return List.copyOf(audit.getOrDefault(customer, List.of()));
    }

    /** Total number of customers currently enrolled. */
    public int enrollmentCount() {
        return store.size();
    }

    // ── internals ────────────────────────────────────────────────────────

    private Preferences withChanges(Preferences current,
                                    boolean paperless,
                                    Channel primary,
                                    Channel fallback,
                                    String address,
                                    Language language,
                                    Frequency frequency,
                                    Set<AccountNumber> combinedGroup) {
        return new Preferences(
                current.customer(),
                paperless,
                primary,
                fallback,
                address,
                language,
                frequency,
                combinedGroup,
                clock.instant());
    }

    private void recordChange(CustomerId customer, String field,
                              String previousValue, String newValue, String actor) {
        var event = new PreferenceChangeEvent(customer, field, previousValue, newValue,
                actor == null ? DEFAULT_ACTOR_SELF : actor, clock.instant());
        audit.computeIfAbsent(customer, k -> Collections.synchronizedList(new ArrayList<>())).add(event);
        log.debug("Preference change {} {}={} -> {} by {}",
                customer, field, previousValue, newValue, event.actor());
    }

    private static String orSelf(String actor) {
        return actor == null || actor.isBlank() ? DEFAULT_ACTOR_SELF : actor;
    }
}
