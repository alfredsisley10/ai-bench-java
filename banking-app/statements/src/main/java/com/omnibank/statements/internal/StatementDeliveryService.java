package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.statements.internal.StatementGenerator.Format;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers rendered statements across multiple channels and tracks the result
 * of each attempt.
 *
 * <p>Supported channels map to the real-world operational split:
 * <ul>
 *   <li>{@link Channel#E_STATEMENT_SFTP} — upload to the customer's SFTP drop</li>
 *   <li>{@link Channel#USPS_PHYSICAL_MAIL} — handoff to the print-and-mail vendor</li>
 *   <li>{@link Channel#SECURE_MESSAGE_PORTAL} — deposit in online-banking inbox</li>
 *   <li>{@link Channel#THIRD_PARTY_AGGREGATOR} — push to Plaid/Finicity/Mint-type
 *       aggregators via partner API</li>
 * </ul>
 *
 * <p>Each attempt produces a {@link DeliveryRecord} whose lifecycle is a small
 * state machine — PENDING → IN_FLIGHT → (DELIVERED | FAILED). Retries increment
 * {@link DeliveryRecord#attemptCount()} and advance the next-retry wall-clock
 * time using exponential back-off with jitter.
 */
public class StatementDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(StatementDeliveryService.class);

    /** Initial delay between first failure and retry. */
    public static final long INITIAL_RETRY_DELAY_MS = 60_000L;

    /** Upper cap so retries never drift into days. */
    public static final long MAX_RETRY_DELAY_MS = 3_600_000L;

    /** Failure count at which a delivery is abandoned and marked FAILED permanently. */
    public static final int MAX_RETRY_ATTEMPTS = 5;

    /** Channels through which a statement can be delivered. */
    public enum Channel {
        E_STATEMENT_SFTP,
        USPS_PHYSICAL_MAIL,
        SECURE_MESSAGE_PORTAL,
        THIRD_PARTY_AGGREGATOR
    }

    /** Lifecycle of a single delivery attempt. */
    public enum DeliveryStatus {
        PENDING,
        IN_FLIGHT,
        DELIVERED,
        FAILED
    }

    /** Mapping of which output formats each channel can carry. */
    private static final Map<Channel, Set<Format>> SUPPORTED_FORMATS;

    static {
        SUPPORTED_FORMATS = new EnumMap<>(Channel.class);
        SUPPORTED_FORMATS.put(Channel.E_STATEMENT_SFTP, EnumSet.of(Format.PDF, Format.PLAIN_TEXT));
        SUPPORTED_FORMATS.put(Channel.USPS_PHYSICAL_MAIL, EnumSet.of(Format.PDF));
        SUPPORTED_FORMATS.put(Channel.SECURE_MESSAGE_PORTAL, EnumSet.of(Format.HTML, Format.PDF));
        SUPPORTED_FORMATS.put(Channel.THIRD_PARTY_AGGREGATOR, EnumSet.of(Format.PDF, Format.PLAIN_TEXT));
    }

    /**
     * Destination spec for a channel. Address contents vary per channel (a USPS
     * address, an SFTP URL, a portal mailbox id) — kept as free-form strings so
     * the service isn't coupled to each channel's address type.
     */
    public record DeliveryDestination(
            Channel channel,
            String primaryAddress,
            String secondaryAddress,
            String description
    ) {
        public DeliveryDestination {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(primaryAddress, "primaryAddress");
            // secondary/description may legitimately be null for some channels
        }
    }

    /** One delivery attempt's full history record. */
    public record DeliveryRecord(
            String deliveryId,
            String statementId,
            AccountNumber account,
            CustomerId customer,
            Channel channel,
            Format format,
            DeliveryStatus status,
            int attemptCount,
            Instant firstAttemptAt,
            Instant lastAttemptAt,
            Instant nextRetryAt,
            String lastError
    ) {
        public DeliveryRecord {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(statementId, "statementId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(status, "status");
        }

        public boolean isTerminal() {
            return status == DeliveryStatus.DELIVERED || status == DeliveryStatus.FAILED;
        }
    }

    /** Pluggable transport. Tests install in-memory implementations. */
    public interface ChannelTransport {
        Channel channel();

        /**
         * Attempt the physical delivery. Returning {@code true} means the vendor
         * accepted the payload; {@code false} means a transient failure that
         * should be retried. Throwing a {@link TransportException} signals a
         * permanent, non-retryable failure (bad address, account closed, etc.).
         */
        boolean deliver(DeliveryDestination destination, RenderedStatement rendered) throws TransportException;
    }

    /** Marker for non-retryable transport failures. */
    public static final class TransportException extends Exception {
        public TransportException(String message) {
            super(message);
        }
    }

    private final Clock clock;
    private final Map<Channel, ChannelTransport> transports = new EnumMap<>(Channel.class);
    private final Map<String, DeliveryRecord> records = new ConcurrentHashMap<>();

    public StatementDeliveryService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Install a transport for one channel. Overwrites an existing binding. */
    public void registerTransport(ChannelTransport transport) {
        Objects.requireNonNull(transport, "transport");
        transports.put(transport.channel(), transport);
    }

    /** Returns the set of channels currently wired with a transport. */
    public Set<Channel> wiredChannels() {
        return EnumSet.copyOf(transports.keySet());
    }

    /**
     * Attempt an initial delivery. Creates a new {@link DeliveryRecord} and
     * drives it through the transport. On transient failure the record's
     * {@code nextRetryAt} is set — callers poll {@link #dueForRetry} to drive
     * retries on a schedule (usually out of the cycle scheduler).
     */
    public DeliveryRecord deliver(RenderedStatement rendered,
                                  CustomerId customer,
                                  DeliveryDestination destination) {
        Objects.requireNonNull(rendered, "rendered");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(destination, "destination");

        Set<Format> supported = SUPPORTED_FORMATS.getOrDefault(destination.channel(), Set.of());
        if (!supported.contains(rendered.format())) {
            throw new IllegalArgumentException(
                    "Channel %s does not accept format %s (supports %s)"
                            .formatted(destination.channel(), rendered.format(), supported));
        }

        String deliveryId = "DLV-" + UUID.randomUUID();
        Instant now = clock.instant();
        DeliveryRecord record = new DeliveryRecord(
                deliveryId,
                rendered.content().statementId(),
                rendered.content().header().account(),
                customer,
                destination.channel(),
                rendered.format(),
                DeliveryStatus.PENDING,
                0,
                now,
                now,
                null,
                null);

        records.put(deliveryId, record);
        log.info("Created delivery record id={} channel={} statement={}",
                deliveryId, destination.channel(), rendered.content().statementId());
        return attempt(record, rendered, destination);
    }

    /**
     * Retry a previously-failed delivery. No-ops for records that are already
     * terminal. Callers supply the same {@link RenderedStatement} to avoid
     * re-rendering on every retry.
     */
    public DeliveryRecord retry(String deliveryId,
                                RenderedStatement rendered,
                                DeliveryDestination destination) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        DeliveryRecord current = records.get(deliveryId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown delivery id: " + deliveryId);
        }
        if (current.isTerminal()) {
            log.debug("Ignoring retry on terminal record id={} status={}", deliveryId, current.status());
            return current;
        }
        if (current.attemptCount() >= MAX_RETRY_ATTEMPTS) {
            DeliveryRecord giveUp = withStatus(current, DeliveryStatus.FAILED,
                    current.lastError() == null ? "Retry budget exhausted" : current.lastError(), null);
            records.put(deliveryId, giveUp);
            return giveUp;
        }
        return attempt(current, rendered, destination);
    }

    /** Retrieve a single delivery record (copy-safe). */
    public DeliveryRecord getRecord(String deliveryId) {
        DeliveryRecord r = records.get(deliveryId);
        if (r == null) {
            throw new IllegalArgumentException("Unknown delivery id: " + deliveryId);
        }
        return r;
    }

    /** All records for a given account. Useful for customer-service lookup. */
    public List<DeliveryRecord> historyFor(AccountNumber account) {
        Objects.requireNonNull(account, "account");
        List<DeliveryRecord> out = new ArrayList<>();
        for (DeliveryRecord r : records.values()) {
            if (r.account().equals(account)) {
                out.add(r);
            }
        }
        Collections.sort(out, (a, b) -> a.firstAttemptAt().compareTo(b.firstAttemptAt()));
        return out;
    }

    /**
     * Delivery records that are eligible for retry now — {@code nextRetryAt}
     * is not null and is in the past. Returned list is a snapshot; callers
     * drive {@link #retry(String, RenderedStatement, DeliveryDestination)}.
     */
    public List<DeliveryRecord> dueForRetry() {
        Instant now = clock.instant();
        List<DeliveryRecord> out = new ArrayList<>();
        for (DeliveryRecord r : records.values()) {
            if (r.status() == DeliveryStatus.PENDING
                    && r.nextRetryAt() != null
                    && !r.nextRetryAt().isAfter(now)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Count of records in a given status — basic operational metric. */
    public long countByStatus(DeliveryStatus status) {
        Objects.requireNonNull(status, "status");
        return records.values().stream().filter(r -> r.status() == status).count();
    }

    // ── internals ────────────────────────────────────────────────────────

    private DeliveryRecord attempt(DeliveryRecord current,
                                   RenderedStatement rendered,
                                   DeliveryDestination destination) {
        ChannelTransport transport = transports.get(destination.channel());
        if (transport == null) {
            DeliveryRecord failed = withStatus(current, DeliveryStatus.FAILED,
                    "No transport registered for channel " + destination.channel(), null);
            records.put(current.deliveryId(), failed);
            return failed;
        }

        Instant now = clock.instant();
        DeliveryRecord inFlight = new DeliveryRecord(
                current.deliveryId(),
                current.statementId(),
                current.account(),
                current.customer(),
                current.channel(),
                current.format(),
                DeliveryStatus.IN_FLIGHT,
                current.attemptCount() + 1,
                current.firstAttemptAt(),
                now,
                null,
                current.lastError());
        records.put(current.deliveryId(), inFlight);

        try {
            boolean accepted = transport.deliver(destination, rendered);
            if (accepted) {
                DeliveryRecord delivered = withStatus(inFlight, DeliveryStatus.DELIVERED, null, null);
                records.put(inFlight.deliveryId(), delivered);
                log.info("Delivered statement {} via {} (attempt {})",
                        current.statementId(), current.channel(), inFlight.attemptCount());
                return delivered;
            }
            // Transient failure — schedule retry with back-off.
            Instant nextRetry = now.plusMillis(backoffMillis(inFlight.attemptCount()));
            DeliveryRecord pending = new DeliveryRecord(
                    inFlight.deliveryId(),
                    inFlight.statementId(),
                    inFlight.account(),
                    inFlight.customer(),
                    inFlight.channel(),
                    inFlight.format(),
                    DeliveryStatus.PENDING,
                    inFlight.attemptCount(),
                    inFlight.firstAttemptAt(),
                    now,
                    nextRetry,
                    "Transport returned not accepted");
            records.put(inFlight.deliveryId(), pending);
            log.warn("Delivery {} transient failure, retry scheduled for {}", inFlight.deliveryId(), nextRetry);
            return pending;
        } catch (TransportException e) {
            DeliveryRecord failed = withStatus(inFlight, DeliveryStatus.FAILED, e.getMessage(), null);
            records.put(inFlight.deliveryId(), failed);
            log.error("Delivery {} permanent failure: {}", inFlight.deliveryId(), e.getMessage());
            return failed;
        } catch (RuntimeException e) {
            // Unexpected runtime — treat as transient until retry budget exhausted.
            if (inFlight.attemptCount() >= MAX_RETRY_ATTEMPTS) {
                DeliveryRecord failed = withStatus(inFlight, DeliveryStatus.FAILED,
                        "Max attempts reached: " + e.getMessage(), null);
                records.put(inFlight.deliveryId(), failed);
                return failed;
            }
            Instant nextRetry = now.plusMillis(backoffMillis(inFlight.attemptCount()));
            DeliveryRecord pending = new DeliveryRecord(
                    inFlight.deliveryId(),
                    inFlight.statementId(),
                    inFlight.account(),
                    inFlight.customer(),
                    inFlight.channel(),
                    inFlight.format(),
                    DeliveryStatus.PENDING,
                    inFlight.attemptCount(),
                    inFlight.firstAttemptAt(),
                    now,
                    nextRetry,
                    e.getMessage());
            records.put(inFlight.deliveryId(), pending);
            return pending;
        }
    }

    private DeliveryRecord withStatus(DeliveryRecord current, DeliveryStatus status,
                                      String error, Instant nextRetry) {
        return new DeliveryRecord(
                current.deliveryId(),
                current.statementId(),
                current.account(),
                current.customer(),
                current.channel(),
                current.format(),
                status,
                current.attemptCount(),
                current.firstAttemptAt(),
                clock.instant(),
                nextRetry,
                error);
    }

    private long backoffMillis(int attemptCount) {
        long base = INITIAL_RETRY_DELAY_MS * (1L << Math.min(attemptCount - 1, 6));
        return Math.min(base, MAX_RETRY_DELAY_MS);
    }
}
