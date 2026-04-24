package com.omnibank.payments.rtp;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-time settlement processing engine for The Clearing House RTP network.
 *
 * <p>RTP settlement is irrevocable and happens in seconds. This engine manages
 * the submission lifecycle with:
 * <ul>
 *   <li>Idempotency enforcement via unique settlement reference tracking</li>
 *   <li>Timeout detection (RTP mandates response within 20 seconds)</li>
 *   <li>Configurable retry logic with exponential backoff for transient failures</li>
 *   <li>Full status tracking from submission through final settlement confirmation</li>
 * </ul>
 *
 * <p>Settlement finality: once the RTP network confirms settlement, the payment
 * is irrevocable. There is no reversal mechanism at the network level; only a
 * separate "Request for Return" message can be sent, which the beneficiary bank
 * may honor at its discretion.
 */
public class RtpSettlementEngine {

    private static final Logger log = LoggerFactory.getLogger(RtpSettlementEngine.class);

    private static final Duration RTP_NETWORK_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(500);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    public enum SettlementStatus {
        PENDING,
        SUBMITTED_TO_NETWORK,
        ACKNOWLEDGED,
        SETTLED,
        REJECTED_BY_NETWORK,
        TIMED_OUT,
        FAILED_RETRIES_EXHAUSTED
    }

    public record SettlementRequest(
            PaymentId paymentId,
            String idempotencyKey,
            String pain001Message,
            Money amount,
            String debtorAgent,
            String creditorAgent
    ) {
        public SettlementRequest {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(pain001Message, "pain001Message");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(debtorAgent, "debtorAgent");
            Objects.requireNonNull(creditorAgent, "creditorAgent");
        }
    }

    public record SettlementResult(
            String settlementReference,
            SettlementStatus status,
            Instant submittedAt,
            Instant completedAt,
            int attemptCount,
            String networkResponseCode,
            String failureReason
    ) {}

    private record SettlementState(
            SettlementRequest request,
            String settlementReference,
            SettlementStatus status,
            Instant submittedAt,
            Instant lastAttemptAt,
            AtomicInteger attemptCount,
            String networkResponseCode,
            String failureReason
    ) {
        SettlementResult toResult(Instant completedAt) {
            return new SettlementResult(
                    settlementReference, status, submittedAt, completedAt,
                    attemptCount.get(), networkResponseCode, failureReason);
        }
    }

    private final Map<String, SettlementState> activeSettlements = new ConcurrentHashMap<>();
    private final Map<String, SettlementResult> completedSettlements = new ConcurrentHashMap<>();
    private final Clock clock;

    public RtpSettlementEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Submits a payment for real-time settlement on the RTP network.
     * Enforces idempotency: if the same idempotencyKey has been seen before,
     * returns the existing result without resubmission.
     */
    public SettlementResult submitForSettlement(SettlementRequest request) {
        // Idempotency check: return completed result if previously processed
        var existing = completedSettlements.get(request.idempotencyKey());
        if (existing != null) {
            log.info("Idempotent hit for settlement request: paymentId={}, key={}",
                    request.paymentId(), request.idempotencyKey());
            return existing;
        }

        // Prevent duplicate in-flight submissions
        if (activeSettlements.containsKey(request.idempotencyKey())) {
            log.warn("Duplicate in-flight settlement request ignored: paymentId={}", request.paymentId());
            var active = activeSettlements.get(request.idempotencyKey());
            return active.toResult(null);
        }

        var settlementRef = generateSettlementReference();
        var now = Timestamp.now(clock);
        var state = new SettlementState(
                request, settlementRef, SettlementStatus.PENDING,
                now, now, new AtomicInteger(0), null, null);

        activeSettlements.put(request.idempotencyKey(), state);
        log.info("Initiating RTP settlement: paymentId={}, ref={}, amount={}",
                request.paymentId(), settlementRef, request.amount());

        return executeWithRetry(state);
    }

    /**
     * Queries the current status of a settlement by its reference.
     */
    public Optional<SettlementResult> queryStatus(String settlementReference) {
        return completedSettlements.values().stream()
                .filter(r -> r.settlementReference().equals(settlementReference))
                .findFirst()
                .or(() -> activeSettlements.values().stream()
                        .filter(s -> s.settlementReference().equals(settlementReference))
                        .map(s -> s.toResult(null))
                        .findFirst());
    }

    /**
     * Checks for timed-out settlements and marks them accordingly.
     * Should be called periodically by a scheduler.
     */
    public int sweepTimedOutSettlements() {
        var now = Timestamp.now(clock);
        int timedOutCount = 0;

        for (var entry : activeSettlements.entrySet()) {
            var state = entry.getValue();
            if (Duration.between(state.submittedAt(), now).compareTo(RTP_NETWORK_TIMEOUT) > 0
                    && state.status() == SettlementStatus.SUBMITTED_TO_NETWORK) {
                var timedOut = new SettlementState(
                        state.request(), state.settlementReference(), SettlementStatus.TIMED_OUT,
                        state.submittedAt(), now, state.attemptCount(),
                        null, "RTP network response not received within 20 seconds");

                var result = timedOut.toResult(now);
                completedSettlements.put(entry.getKey(), result);
                activeSettlements.remove(entry.getKey());

                log.error("RTP settlement timed out: ref={}, paymentId={}",
                        state.settlementReference(), state.request().paymentId());
                timedOutCount++;
            }
        }
        return timedOutCount;
    }

    private SettlementResult executeWithRetry(SettlementState state) {
        Duration retryDelay = INITIAL_RETRY_DELAY;

        while (state.attemptCount().get() < MAX_RETRY_ATTEMPTS) {
            int attempt = state.attemptCount().incrementAndGet();
            var now = Timestamp.now(clock);

            log.info("RTP settlement attempt {}/{}: ref={}, paymentId={}",
                    attempt, MAX_RETRY_ATTEMPTS, state.settlementReference(),
                    state.request().paymentId());

            try {
                var networkResponse = sendToRtpNetwork(state);

                if (isSuccessResponse(networkResponse)) {
                    var result = new SettlementResult(
                            state.settlementReference(), SettlementStatus.SETTLED,
                            state.submittedAt(), Timestamp.now(clock),
                            attempt, networkResponse, null);

                    completedSettlements.put(state.request().idempotencyKey(), result);
                    activeSettlements.remove(state.request().idempotencyKey());

                    log.info("RTP settlement confirmed: ref={}, responseCode={}",
                            state.settlementReference(), networkResponse);
                    return result;
                }

                if (isRejection(networkResponse)) {
                    var failureReason = mapRejectionReason(networkResponse);
                    var result = new SettlementResult(
                            state.settlementReference(), SettlementStatus.REJECTED_BY_NETWORK,
                            state.submittedAt(), Timestamp.now(clock),
                            attempt, networkResponse, failureReason);

                    completedSettlements.put(state.request().idempotencyKey(), result);
                    activeSettlements.remove(state.request().idempotencyKey());

                    log.warn("RTP settlement rejected: ref={}, reason={}",
                            state.settlementReference(), failureReason);
                    return result;
                }

                // Transient failure — retry with backoff
                log.warn("RTP transient failure on attempt {}: ref={}, response={}",
                        attempt, state.settlementReference(), networkResponse);

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    sleepForRetry(retryDelay);
                    retryDelay = Duration.ofMillis((long) (retryDelay.toMillis() * BACKOFF_MULTIPLIER));
                }

            } catch (Exception e) {
                log.error("RTP network communication error on attempt {}: ref={}",
                        attempt, state.settlementReference(), e);

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    sleepForRetry(retryDelay);
                    retryDelay = Duration.ofMillis((long) (retryDelay.toMillis() * BACKOFF_MULTIPLIER));
                }
            }
        }

        // All retries exhausted
        var result = new SettlementResult(
                state.settlementReference(), SettlementStatus.FAILED_RETRIES_EXHAUSTED,
                state.submittedAt(), Timestamp.now(clock),
                state.attemptCount().get(), null, "Maximum retry attempts exhausted");

        completedSettlements.put(state.request().idempotencyKey(), result);
        activeSettlements.remove(state.request().idempotencyKey());

        log.error("RTP settlement failed after {} attempts: ref={}, paymentId={}",
                MAX_RETRY_ATTEMPTS, state.settlementReference(), state.request().paymentId());
        return result;
    }

    /**
     * Sends the pain.001 message to the RTP network gateway.
     * In production this would be an HTTP/MQ call to TCH; returns a response code string.
     */
    private String sendToRtpNetwork(SettlementState state) {
        // Network integration point — in production, this calls the TCH RTP gateway
        // via mutual TLS over the bank's dedicated RTP connectivity.
        log.debug("Sending pain.001 to TCH RTP gateway: ref={}, debtorAgent={}, creditorAgent={}",
                state.settlementReference(),
                state.request().debtorAgent(),
                state.request().creditorAgent());

        // Simulate network call — production implementation would use the bank's
        // message queue or REST gateway to TCH
        return "ACCP";
    }

    private boolean isSuccessResponse(String responseCode) {
        return "ACCP".equals(responseCode) || "ACSP".equals(responseCode) || "ACSC".equals(responseCode);
    }

    private boolean isRejection(String responseCode) {
        return "RJCT".equals(responseCode);
    }

    private String mapRejectionReason(String responseCode) {
        return "Payment rejected by beneficiary bank or network";
    }

    private String generateSettlementReference() {
        return "RTPSTL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private void sleepForRetry(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Settlement retry interrupted", e);
        }
    }

    /**
     * Returns count of currently active (in-flight) settlements.
     * Useful for monitoring and circuit-breaker decisions.
     */
    public int activeSettlementCount() {
        return activeSettlements.size();
    }

    /**
     * Returns count of completed settlements (success + failure).
     */
    public int completedSettlementCount() {
        return completedSettlements.size();
    }
}
