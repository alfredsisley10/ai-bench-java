package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.statements.internal.StatementDeliveryService.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects statement-based fraud signals.
 *
 * <p>Three family of flags are produced:
 * <ul>
 *   <li>{@link FlagType#ADDRESS_MISMATCH} — the statement's mailing address
 *       does not match the address of record. Fraudsters frequently change
 *       statement delivery to a drop address while leaving login-address
 *       records intact.</li>
 *   <li>{@link FlagType#SUDDEN_CHANNEL_CHANGE} — a customer who has used
 *       one channel for months suddenly flips to a different one, especially
 *       to a third-party aggregator.</li>
 *   <li>{@link FlagType#EXCESSIVE_COPY_REQUESTS} — more than
 *       {@link #COPY_REQUEST_THRESHOLD} statement-copy requests in
 *       {@link #COPY_REQUEST_WINDOW_DAYS} days is a classic pretext-calling
 *       pattern.</li>
 * </ul>
 */
public class StatementFraudFlagging {

    private static final Logger log = LoggerFactory.getLogger(StatementFraudFlagging.class);

    /** Number of copy requests within the rolling window that raises a flag. */
    public static final int COPY_REQUEST_THRESHOLD = 5;

    /** Rolling window for copy-request counting. */
    public static final int COPY_REQUEST_WINDOW_DAYS = 30;

    /** Flags surfaced by this service. */
    public enum FlagType {
        ADDRESS_MISMATCH,
        SUDDEN_CHANNEL_CHANGE,
        EXCESSIVE_COPY_REQUESTS
    }

    /** Severity scale for downstream triage systems. */
    public enum Severity {
        INFO,
        REVIEW,
        INVESTIGATE
    }

    /** One fraud flag record. */
    public record FraudFlag(
            String flagId,
            CustomerId customer,
            AccountNumber account,
            FlagType type,
            Severity severity,
            String description,
            Instant raisedAt
    ) {
        public FraudFlag {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(raisedAt, "raisedAt");
        }
    }

    /** Snapshot of the customer's last-observed channel usage. */
    private static final class ChannelHistory {
        Channel lastChannel;
        Instant lastSeenAt;
        int consecutiveUses;
    }

    /** Rolling window of copy-request timestamps. */
    private static final class CopyHistory {
        final Deque<Instant> timestamps = new ArrayDeque<>();
    }

    private final Clock clock;
    private final Map<CustomerId, ChannelHistory> channelHistory = new ConcurrentHashMap<>();
    private final Map<CustomerId, CopyHistory> copyHistory = new ConcurrentHashMap<>();
    private final List<FraudFlag> raised = Collections_synchronized();

    private static List<FraudFlag> Collections_synchronized() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    public StatementFraudFlagging(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Check whether the statement's mailing address matches the address of
     * record. Mismatches yield an ADDRESS_MISMATCH flag at INVESTIGATE severity.
     */
    public List<FraudFlag> inspectMailingAddress(CustomerId customer,
                                                 AccountNumber account,
                                                 String statementAddress,
                                                 String addressOfRecord) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(statementAddress, "statementAddress");
        Objects.requireNonNull(addressOfRecord, "addressOfRecord");
        if (addressesMatch(statementAddress, addressOfRecord)) {
            return List.of();
        }
        FraudFlag flag = new FraudFlag(
                "FF-" + java.util.UUID.randomUUID(),
                customer, account,
                FlagType.ADDRESS_MISMATCH,
                Severity.INVESTIGATE,
                "Statement mailing '%s' does not match address of record '%s'"
                        .formatted(statementAddress, addressOfRecord),
                clock.instant());
        raised.add(flag);
        log.warn("Address mismatch flag raised for {} on account {}", customer, account);
        return List.of(flag);
    }

    /**
     * Record a channel use for the customer and raise a flag if this breaks a
     * stable pattern. A stable pattern is defined as at least three
     * consecutive uses of the prior channel.
     */
    public List<FraudFlag> observeChannelUse(CustomerId customer,
                                             AccountNumber account,
                                             Channel channel) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(channel, "channel");

        ChannelHistory hist = channelHistory.computeIfAbsent(customer, k -> new ChannelHistory());
        List<FraudFlag> flags = new ArrayList<>();
        synchronized (hist) {
            if (hist.lastChannel == null) {
                hist.lastChannel = channel;
                hist.lastSeenAt = clock.instant();
                hist.consecutiveUses = 1;
                return flags;
            }
            if (hist.lastChannel == channel) {
                hist.consecutiveUses++;
                hist.lastSeenAt = clock.instant();
                return flags;
            }
            // Channel change.
            Severity severity = channel == Channel.THIRD_PARTY_AGGREGATOR
                    ? Severity.INVESTIGATE
                    : (hist.consecutiveUses >= 3 ? Severity.REVIEW : Severity.INFO);
            if (hist.consecutiveUses >= 3 || channel == Channel.THIRD_PARTY_AGGREGATOR) {
                FraudFlag flag = new FraudFlag(
                        "FF-" + java.util.UUID.randomUUID(),
                        customer, account,
                        FlagType.SUDDEN_CHANNEL_CHANGE,
                        severity,
                        "Channel changed from %s (used %d times) to %s"
                                .formatted(hist.lastChannel, hist.consecutiveUses, channel),
                        clock.instant());
                flags.add(flag);
                raised.add(flag);
                log.warn("Channel change flag raised for {} ({}->{})",
                        customer, hist.lastChannel, channel);
            }
            hist.lastChannel = channel;
            hist.lastSeenAt = clock.instant();
            hist.consecutiveUses = 1;
        }
        return flags;
    }

    /**
     * Record a statement-copy request. Flags when the rolling 30-day count
     * exceeds {@link #COPY_REQUEST_THRESHOLD}.
     */
    public List<FraudFlag> observeCopyRequest(CustomerId customer, AccountNumber account) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");

        Instant now = clock.instant();
        CopyHistory hist = copyHistory.computeIfAbsent(customer, k -> new CopyHistory());
        List<FraudFlag> flags = new ArrayList<>();
        synchronized (hist) {
            hist.timestamps.add(now);
            Instant cutoff = now.minus(COPY_REQUEST_WINDOW_DAYS, ChronoUnit.DAYS);
            while (!hist.timestamps.isEmpty() && hist.timestamps.peekFirst().isBefore(cutoff)) {
                hist.timestamps.pollFirst();
            }
            if (hist.timestamps.size() > COPY_REQUEST_THRESHOLD) {
                FraudFlag flag = new FraudFlag(
                        "FF-" + java.util.UUID.randomUUID(),
                        customer, account,
                        FlagType.EXCESSIVE_COPY_REQUESTS,
                        Severity.REVIEW,
                        "%d copy requests in the last %d days (threshold %d)"
                                .formatted(hist.timestamps.size(), COPY_REQUEST_WINDOW_DAYS,
                                        COPY_REQUEST_THRESHOLD),
                        now);
                flags.add(flag);
                raised.add(flag);
                log.warn("Excessive copy-request flag raised for {} ({} requests)",
                        customer, hist.timestamps.size());
            }
        }
        return flags;
    }

    /** All flags ever raised by this service (unbounded; in-memory only). */
    public List<FraudFlag> raisedFlags() {
        synchronized (raised) {
            return List.copyOf(raised);
        }
    }

    /** Count flags for a given customer. Useful for dashboards. */
    public long countFor(CustomerId customer) {
        Objects.requireNonNull(customer, "customer");
        synchronized (raised) {
            return raised.stream().filter(f -> f.customer().equals(customer)).count();
        }
    }

    /**
     * Reset a customer's channel + copy history. Invoked when an account is
     * closed or after fraud-ops manually clears a flag.
     */
    public void clearHistory(CustomerId customer) {
        Objects.requireNonNull(customer, "customer");
        channelHistory.remove(customer);
        copyHistory.remove(customer);
    }

    private static boolean addressesMatch(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String addr) {
        return addr.toLowerCase().replaceAll("[\\s,\\.]+", " ").trim();
    }
}
