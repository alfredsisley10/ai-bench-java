package com.omnibank.batch.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Batch-specific retry handler that provides fine-grained control over
 * retry behaviour at the item level vs chunk level. Failed items that
 * exhaust their retries are routed to a dead-letter queue (DLQ) for
 * manual review or scheduled reprocessing.
 *
 * <p>This differs from {@link com.omnibank.shared.resilience.RetryPolicy}
 * which is designed for single-call retry. {@code BatchRetryHandler}
 * manages retry state across thousands of items within a batch run,
 * with per-item-type backoff, failure classification, and DLQ routing.
 *
 * <p>Key features:
 * <ul>
 *   <li><b>Item-level retry</b> — individual items are retried without
 *       re-processing the entire chunk</li>
 *   <li><b>Chunk-level retry</b> — entire chunks can be retried when the
 *       failure is likely systemic (e.g. database connection lost)</li>
 *   <li><b>Dead-letter queue</b> — failed items are persisted for later
 *       review with full error context</li>
 *   <li><b>Exponential backoff per item type</b> — different item types
 *       can have different retry schedules</li>
 *   <li><b>Scheduled retry</b> — items in the DLQ can be automatically
 *       retried on a configurable schedule</li>
 * </ul>
 */
public class BatchRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(BatchRetryHandler.class);

    private final ConcurrentHashMap<String, RetryConfig> retryConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ItemRetryState> itemRetryStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<DeadLetterEntry> deadLetterQueue = new ConcurrentLinkedQueue<>();
    private final RetryMetrics metrics = new RetryMetrics();
    private final ScheduledExecutorService retryScheduler;

    /** Registered reprocessors for DLQ items, keyed by item type. */
    private final ConcurrentHashMap<String, Function<DeadLetterEntry, Boolean>> reprocessors =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Retry configuration for a specific item type.
     *
     * @param itemType         logical type (e.g. "ach-entry", "interest-accrual")
     * @param maxItemRetries   max retries per individual item
     * @param maxChunkRetries  max retries for an entire chunk
     * @param baseDelay        initial backoff delay
     * @param maxDelay         ceiling for backoff delay
     * @param backoffMultiplier exponential multiplier
     * @param retryablePredicate  determines if an exception is retryable
     * @param criticalPredicate   determines if an exception is critical (fail-fast)
     */
    public record RetryConfig(
            String itemType,
            int maxItemRetries,
            int maxChunkRetries,
            Duration baseDelay,
            Duration maxDelay,
            double backoffMultiplier,
            Predicate<Throwable> retryablePredicate,
            Predicate<Throwable> criticalPredicate
    ) {
        public RetryConfig {
            Objects.requireNonNull(itemType, "itemType");
            Objects.requireNonNull(baseDelay, "baseDelay");
            Objects.requireNonNull(maxDelay, "maxDelay");
            if (maxItemRetries < 0) throw new IllegalArgumentException("maxItemRetries must be >= 0");
            if (maxChunkRetries < 0) throw new IllegalArgumentException("maxChunkRetries must be >= 0");
            if (backoffMultiplier <= 0) throw new IllegalArgumentException("backoffMultiplier must be > 0");
            if (retryablePredicate == null) retryablePredicate = ex -> true;
            if (criticalPredicate == null) criticalPredicate = ex -> false;
        }

        public static RetryConfig defaults(String itemType) {
            return new RetryConfig(itemType, 3, 2,
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0,
                    ex -> !(ex instanceof InterruptedException),
                    ex -> false);
        }

        public static RetryConfig withCriticalErrors(String itemType,
                                                      Predicate<Throwable> critical) {
            return new RetryConfig(itemType, 3, 1,
                    Duration.ofSeconds(1), Duration.ofSeconds(15), 2.0,
                    ex -> !(ex instanceof InterruptedException),
                    critical);
        }
    }

    // -------------------------------------------------------------------
    //  Item retry state
    // -------------------------------------------------------------------

    private static final class ItemRetryState {
        private final String itemId;
        private final String itemType;
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private volatile Throwable lastError;
        private volatile Instant lastAttemptAt;
        private volatile Instant nextRetryAt;

        ItemRetryState(String itemId, String itemType) {
            this.itemId = itemId;
            this.itemType = itemType;
        }

        int recordAttempt(Throwable error, Duration nextDelay) {
            int attempt = attemptCount.incrementAndGet();
            this.lastError = error;
            this.lastAttemptAt = Instant.now();
            this.nextRetryAt = Instant.now().plus(nextDelay);
            return attempt;
        }

        boolean isReadyForRetry() {
            return nextRetryAt == null || Instant.now().isAfter(nextRetryAt);
        }
    }

    // -------------------------------------------------------------------
    //  Dead letter entry
    // -------------------------------------------------------------------

    /**
     * An item that has exhausted all retries and needs manual intervention
     * or scheduled reprocessing.
     *
     * @param itemId        unique identifier of the failed item
     * @param itemType      logical type for routing to the correct reprocessor
     * @param originalPayload   serialised representation of the item
     * @param errorMessage  last error message
     * @param errorClass    last exception class name
     * @param attemptCount  total attempts made
     * @param firstFailedAt when the item first failed
     * @param lastFailedAt  when the item last failed
     * @param metadata      additional context (e.g. batch ID, partition)
     */
    public record DeadLetterEntry(
            String itemId,
            String itemType,
            String originalPayload,
            String errorMessage,
            String errorClass,
            int attemptCount,
            Instant firstFailedAt,
            Instant lastFailedAt,
            Map<String, String> metadata
    ) {
        public DeadLetterEntry {
            Objects.requireNonNull(itemId);
            Objects.requireNonNull(itemType);
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }

    // -------------------------------------------------------------------
    //  Metrics
    // -------------------------------------------------------------------

    public static final class RetryMetrics {
        private final LongAdder totalItemsProcessed = new LongAdder();
        private final LongAdder totalItemRetries = new LongAdder();
        private final LongAdder totalChunkRetries = new LongAdder();
        private final LongAdder itemsRetriedSuccessfully = new LongAdder();
        private final LongAdder itemsSentToDlq = new LongAdder();
        private final LongAdder dlqReprocessed = new LongAdder();
        private final LongAdder dlqReprocessFailed = new LongAdder();
        private final LongAdder criticalErrors = new LongAdder();

        public void recordItemProcessed()       { totalItemsProcessed.increment(); }
        public void recordItemRetry()            { totalItemRetries.increment(); }
        public void recordChunkRetry()           { totalChunkRetries.increment(); }
        public void recordItemRetrySuccess()     { itemsRetriedSuccessfully.increment(); }
        public void recordSentToDlq()            { itemsSentToDlq.increment(); }
        public void recordDlqReprocessed()       { dlqReprocessed.increment(); }
        public void recordDlqReprocessFailed()   { dlqReprocessFailed.increment(); }
        public void recordCriticalError()        { criticalErrors.increment(); }

        public long totalItemsProcessed()       { return totalItemsProcessed.sum(); }
        public long totalItemRetries()          { return totalItemRetries.sum(); }
        public long totalChunkRetries()         { return totalChunkRetries.sum(); }
        public long itemsRetriedSuccessfully()  { return itemsRetriedSuccessfully.sum(); }
        public long itemsSentToDlq()            { return itemsSentToDlq.sum(); }
        public long dlqReprocessed()            { return dlqReprocessed.sum(); }
        public long dlqReprocessFailed()        { return dlqReprocessFailed.sum(); }
        public long criticalErrors()            { return criticalErrors.sum(); }

        public long dlqDepth()     { return itemsSentToDlq.sum() - dlqReprocessed.sum(); }
        public double retryRate() {
            long total = totalItemsProcessed.sum();
            return total == 0 ? 0.0 : (totalItemRetries.sum() * 100.0) / total;
        }

        @Override
        public String toString() {
            return ("RetryMetrics[processed=%d, itemRetries=%d, chunkRetries=%d, " +
                    "retrySuccess=%d, dlqDepth=%d, critical=%d]")
                    .formatted(totalItemsProcessed(), totalItemRetries(),
                               totalChunkRetries(), itemsRetriedSuccessfully(),
                               dlqDepth(), criticalErrors());
        }
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public BatchRetryHandler() {
        // JDK 17 cross-compat: Thread.ofPlatform() is JEP 444 (21+).
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-retry-scheduler");
            t.setDaemon(true);
            return t;
        });
        /* Schedule DLQ reprocessing every 5 minutes */
        this.retryScheduler.scheduleAtFixedRate(
                this::reprocessDeadLetterQueue, 5, 5, TimeUnit.MINUTES);
    }

    // -------------------------------------------------------------------
    //  Configuration registration
    // -------------------------------------------------------------------

    public void registerConfig(RetryConfig config) {
        retryConfigs.put(config.itemType(), config);
        log.info("[BatchRetryHandler] Registered retry config for itemType=[{}]: " +
                 "maxItemRetries={}, maxChunkRetries={}",
                 config.itemType(), config.maxItemRetries(), config.maxChunkRetries());
    }

    public void registerReprocessor(String itemType,
                                     Function<DeadLetterEntry, Boolean> reprocessor) {
        reprocessors.put(itemType, reprocessor);
    }

    // -------------------------------------------------------------------
    //  Item-level retry
    // -------------------------------------------------------------------

    /**
     * Represents the decision made after an item processing failure.
     */
    public sealed interface RetryDecision
            permits RetryDecision.Retry, RetryDecision.SendToDlq,
                    RetryDecision.FailFast, RetryDecision.Skip {

        record Retry(Duration delay, int attemptNumber) implements RetryDecision {}
        record SendToDlq(String reason) implements RetryDecision {}
        record FailFast(String reason) implements RetryDecision {}
        record Skip(String reason) implements RetryDecision {}
    }

    /**
     * Evaluates what to do after an item processing failure.
     *
     * @param itemId    unique item identifier
     * @param itemType  logical item type (must match a registered config)
     * @param error     the exception from processing
     * @return the retry decision
     */
    public RetryDecision evaluateItemFailure(String itemId, String itemType, Throwable error) {
        RetryConfig config = retryConfigs.getOrDefault(itemType, RetryConfig.defaults(itemType));

        /* Check for critical errors first */
        if (config.criticalPredicate().test(error)) {
            metrics.recordCriticalError();
            log.error("[BatchRetryHandler] Critical error for item [{}] type=[{}]: {}",
                      itemId, itemType, error.getMessage());
            return new RetryDecision.FailFast(
                    "Critical error: " + error.getClass().getSimpleName());
        }

        /* Check if the error is retryable at all */
        if (!config.retryablePredicate().test(error)) {
            return new RetryDecision.Skip(
                    "Non-retryable error: " + error.getClass().getSimpleName());
        }

        /* Get or create retry state */
        String stateKey = itemType + "::" + itemId;
        ItemRetryState state = itemRetryStates.computeIfAbsent(stateKey,
                k -> new ItemRetryState(itemId, itemType));

        /* Compute backoff delay */
        Duration delay = computeBackoff(config, state.attemptCount.get());
        int attemptNumber = state.recordAttempt(error, delay);

        if (attemptNumber > config.maxItemRetries()) {
            /* Exhausted retries — route to DLQ */
            metrics.recordSentToDlq();
            routeToDlq(itemId, itemType, error, attemptNumber, null);
            itemRetryStates.remove(stateKey);
            return new RetryDecision.SendToDlq(
                    "Exhausted %d retries".formatted(config.maxItemRetries()));
        }

        metrics.recordItemRetry();
        log.debug("[BatchRetryHandler] Item [{}] type=[{}] retry #{}, delay={}",
                  itemId, itemType, attemptNumber, delay);
        return new RetryDecision.Retry(delay, attemptNumber);
    }

    /**
     * Records a successful retry for metrics tracking.
     */
    public void recordItemRetrySuccess(String itemId, String itemType) {
        metrics.recordItemRetrySuccess();
        metrics.recordItemProcessed();
        String stateKey = itemType + "::" + itemId;
        itemRetryStates.remove(stateKey);
    }

    // -------------------------------------------------------------------
    //  Chunk-level retry
    // -------------------------------------------------------------------

    /**
     * Evaluates whether an entire chunk should be retried after a systemic
     * failure (e.g. database connection lost, network partition).
     *
     * @param chunkId   unique chunk identifier
     * @param itemType  the item type being processed in this chunk
     * @param error     the systemic error
     * @return retry decision for the chunk
     */
    public RetryDecision evaluateChunkFailure(String chunkId, String itemType, Throwable error) {
        RetryConfig config = retryConfigs.getOrDefault(itemType, RetryConfig.defaults(itemType));

        String stateKey = "chunk::" + chunkId;
        ItemRetryState state = itemRetryStates.computeIfAbsent(stateKey,
                k -> new ItemRetryState(chunkId, itemType));

        Duration delay = computeBackoff(config, state.attemptCount.get());
        int attemptNumber = state.recordAttempt(error, delay);

        if (attemptNumber > config.maxChunkRetries()) {
            log.warn("[BatchRetryHandler] Chunk [{}] exhausted {} chunk retries",
                     chunkId, config.maxChunkRetries());
            itemRetryStates.remove(stateKey);
            return new RetryDecision.FailFast(
                    "Chunk exhausted %d retries".formatted(config.maxChunkRetries()));
        }

        metrics.recordChunkRetry();
        log.info("[BatchRetryHandler] Chunk [{}] retry #{}, delay={}",
                 chunkId, attemptNumber, delay);
        return new RetryDecision.Retry(delay, attemptNumber);
    }

    // -------------------------------------------------------------------
    //  Dead letter queue
    // -------------------------------------------------------------------

    /**
     * Routes a failed item to the dead-letter queue.
     */
    public void routeToDlq(String itemId, String itemType, Throwable error,
                            int attemptCount, Map<String, String> metadata) {
        var entry = new DeadLetterEntry(
                itemId, itemType, null,
                error.getMessage(),
                error.getClass().getName(),
                attemptCount,
                Instant.now(), Instant.now(),
                metadata != null ? metadata : Map.of());
        deadLetterQueue.add(entry);
        log.warn("[BatchRetryHandler] Item [{}] type=[{}] routed to DLQ after {} attempts: {}",
                 itemId, itemType, attemptCount, error.getMessage());
    }

    /**
     * Returns an immutable snapshot of the current DLQ contents.
     */
    public List<DeadLetterEntry> dlqSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(deadLetterQueue));
    }

    /**
     * Manually reprocesses a specific DLQ entry.
     */
    public boolean reprocessDlqEntry(DeadLetterEntry entry) {
        var reprocessor = reprocessors.get(entry.itemType());
        if (reprocessor == null) {
            log.warn("[BatchRetryHandler] No reprocessor registered for itemType=[{}]",
                     entry.itemType());
            return false;
        }

        try {
            boolean success = reprocessor.apply(entry);
            if (success) {
                deadLetterQueue.remove(entry);
                metrics.recordDlqReprocessed();
                log.info("[BatchRetryHandler] DLQ item [{}] reprocessed successfully",
                         entry.itemId());
            } else {
                metrics.recordDlqReprocessFailed();
            }
            return success;
        } catch (Exception ex) {
            metrics.recordDlqReprocessFailed();
            log.error("[BatchRetryHandler] DLQ reprocessing failed for item [{}]: {}",
                      entry.itemId(), ex.getMessage());
            return false;
        }
    }

    /**
     * Attempts to reprocess all items in the DLQ.
     */
    public void reprocessDeadLetterQueue() {
        if (deadLetterQueue.isEmpty()) return;

        log.info("[BatchRetryHandler] Reprocessing DLQ: {} entries", deadLetterQueue.size());
        int processed = 0, failed = 0;

        /* Process a copy to avoid concurrent modification */
        var snapshot = new ArrayList<>(deadLetterQueue);
        for (DeadLetterEntry entry : snapshot) {
            if (reprocessDlqEntry(entry)) {
                processed++;
            } else {
                failed++;
            }
        }

        log.info("[BatchRetryHandler] DLQ reprocessing complete: {} succeeded, {} failed",
                 processed, failed);
    }

    // -------------------------------------------------------------------
    //  Backoff computation
    // -------------------------------------------------------------------

    private Duration computeBackoff(RetryConfig config, int currentAttempt) {
        double delayMs = config.baseDelay().toMillis()
                * Math.pow(config.backoffMultiplier(), currentAttempt);
        long capped = Math.min((long) delayMs, config.maxDelay().toMillis());

        /* Add random jitter (up to 25%) to prevent thundering herd */
        long jitter = (long) (capped * 0.25 * Math.random());
        return Duration.ofMillis(capped + jitter);
    }

    // -------------------------------------------------------------------
    //  Metrics access
    // -------------------------------------------------------------------

    public RetryMetrics metrics() { return metrics; }

    // -------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------

    public void shutdown() {
        retryScheduler.shutdown();
        try {
            if (!retryScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resets all retry state. Intended for use between batch runs or
     * in tests.
     */
    public void reset() {
        itemRetryStates.clear();
        deadLetterQueue.clear();
        log.info("[BatchRetryHandler] Reset all retry state and DLQ");
    }
}
