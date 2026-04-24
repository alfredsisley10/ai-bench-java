package com.omnibank.batch.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates batch processing jobs with checkpoint/restart, parallel
 * partitioned execution, error thresholds, compensation, and job
 * dependency management. Designed for the overnight batch window in a
 * large banking operation (EOD accruals, statement generation, regulatory
 * reporting, etc.).
 *
 * <p>Key capabilities:
 * <ul>
 *   <li><b>Checkpoint/restart</b> — each job persists progress so that
 *       a failed run can resume from the last checkpoint</li>
 *   <li><b>Parallel partitioned execution</b> — large datasets are split
 *       into partitions processed on virtual threads</li>
 *   <li><b>Error thresholds</b> — configurable skip limits and
 *       fail-fast-on-critical-error policies</li>
 *   <li><b>Compensation</b> — on partial failure, registered compensating
 *       actions are invoked to roll back side effects</li>
 *   <li><b>Job dependency graph</b> — jobs declare prerequisites; the
 *       orchestrator resolves execution order via topological sort</li>
 * </ul>
 */
public class BatchJobOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BatchJobOrchestrator.class);

    private final ExecutorService partitionExecutor;
    private final ConcurrentHashMap<String, JobDefinition<?>> registeredJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CheckpointStore> checkpointStores = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Core types
    // -------------------------------------------------------------------

    /**
     * Status of a batch job execution.
     */
    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED, PARTIALLY_COMPLETED, COMPENSATED, SKIPPED
    }

    /**
     * Checkpoint representing the last known-good state of a running job.
     *
     * @param jobName         which job this checkpoint belongs to
     * @param partitionId     partition identifier (or "main" for non-partitioned)
     * @param lastProcessedId last item/offset that was fully processed
     * @param itemsProcessed  count of items processed so far
     * @param savedAt         when the checkpoint was persisted
     * @param metadata        arbitrary state needed for resume
     */
    public record Checkpoint(
            String jobName,
            String partitionId,
            String lastProcessedId,
            long itemsProcessed,
            Instant savedAt,
            Map<String, String> metadata
    ) {
        public Checkpoint {
            Objects.requireNonNull(jobName);
            Objects.requireNonNull(partitionId);
            Objects.requireNonNull(savedAt);
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }

    /**
     * In-memory checkpoint store. In production this would be backed by
     * a database table for crash recovery.
     */
    public static final class CheckpointStore {
        private final ConcurrentHashMap<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

        public void save(Checkpoint cp) {
            checkpoints.put(cp.jobName() + "::" + cp.partitionId(), cp);
        }

        public Checkpoint get(String jobName, String partitionId) {
            return checkpoints.get(jobName + "::" + partitionId);
        }

        public void clear(String jobName) {
            checkpoints.entrySet().removeIf(e -> e.getKey().startsWith(jobName + "::"));
        }
    }

    /**
     * Definition of a batch job that can be orchestrated.
     */
    public record JobDefinition<T>(
            String name,
            int partitionCount,
            int skipLimit,
            boolean failFastOnCriticalError,
            Set<String> dependencies,
            PartitionedProcessor<T> processor,
            Consumer<JobExecutionResult> compensator,
            int checkpointInterval
    ) {
        public JobDefinition {
            Objects.requireNonNull(name, "name");
            if (partitionCount <= 0) throw new IllegalArgumentException("partitionCount must be > 0");
            if (skipLimit < 0) throw new IllegalArgumentException("skipLimit must be >= 0");
            dependencies = dependencies != null ? Set.copyOf(dependencies) : Set.of();
            Objects.requireNonNull(processor, "processor");
            if (checkpointInterval <= 0) throw new IllegalArgumentException("checkpointInterval must be > 0");
        }
    }

    /**
     * Processor that handles a single partition of a batch job.
     */
    @FunctionalInterface
    public interface PartitionedProcessor<T> {
        /**
         * @param partitionId     which partition to process
         * @param businessDate    the batch business date
         * @param resumeFrom      checkpoint to resume from (null on fresh start)
         * @param progressCallback  callback to report item-level progress
         * @return partition result
         */
        PartitionResult<T> process(String partitionId, LocalDate businessDate,
                                    Checkpoint resumeFrom,
                                    Consumer<PartitionProgress> progressCallback);
    }

    /**
     * Progress report from a partition.
     */
    public record PartitionProgress(
            String partitionId,
            long itemsProcessed,
            long itemsSkipped,
            long itemsFailed,
            String lastProcessedId
    ) {}

    /**
     * Result from a single partition's execution.
     */
    public record PartitionResult<T>(
            String partitionId,
            JobStatus status,
            long itemsProcessed,
            long itemsSkipped,
            List<String> errors,
            Duration elapsed,
            T resultData
    ) {
        public PartitionResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
    }

    /**
     * Overall result of a batch job execution across all partitions.
     */
    public record JobExecutionResult(
            String jobName,
            JobStatus status,
            LocalDate businessDate,
            long totalItemsProcessed,
            long totalItemsSkipped,
            long totalItemsFailed,
            Duration totalElapsed,
            List<PartitionResult<?>> partitionResults,
            List<String> errors,
            Instant startedAt,
            Instant completedAt
    ) {
        public JobExecutionResult {
            partitionResults = List.copyOf(partitionResults);
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public BatchJobOrchestrator() {
        // JDK 17 cross-compat: cached platform threads. Virtual threads
        // (JEP 444) finalized in JDK 21.
        this.partitionExecutor = Executors.newCachedThreadPool();
    }

    // -------------------------------------------------------------------
    //  Job registration
    // -------------------------------------------------------------------

    public <T> void registerJob(JobDefinition<T> job) {
        registeredJobs.put(job.name(), job);
        checkpointStores.putIfAbsent(job.name(), new CheckpointStore());
        log.info("[BatchOrchestrator] Registered job [{}]: partitions={}, skipLimit={}, deps={}",
                 job.name(), job.partitionCount(), job.skipLimit(), job.dependencies());
    }

    // -------------------------------------------------------------------
    //  Execution
    // -------------------------------------------------------------------

    /**
     * Executes a single job with checkpoint/restart and parallel partitions.
     */
    @SuppressWarnings("unchecked")
    public <T> JobExecutionResult executeJob(String jobName, LocalDate businessDate) {
        JobDefinition<T> job = (JobDefinition<T>) registeredJobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }

        /* Check dependencies */
        for (String dep : job.dependencies()) {
            // In a full implementation, verify dep completed successfully
            log.debug("[BatchOrchestrator] Job [{}] depends on [{}]", jobName, dep);
        }

        log.info("[BatchOrchestrator] Starting job [{}] for businessDate={}, partitions={}",
                 jobName, businessDate, job.partitionCount());

        Instant startedAt = Instant.now();
        CheckpointStore cpStore = checkpointStores.get(jobName);
        var partitionFutures = new ArrayList<CompletableFuture<PartitionResult<T>>>();

        AtomicLong globalSkipCount = new AtomicLong(0);
        AtomicReference<JobStatus> failFastSignal = new AtomicReference<>(null);

        for (int p = 0; p < job.partitionCount(); p++) {
            String partitionId = "partition-" + p;
            Checkpoint resumeFrom = cpStore.get(jobName, partitionId);

            CompletableFuture<PartitionResult<T>> future =
                    CompletableFuture.supplyAsync(() -> {
                        if (failFastSignal.get() != null) {
                            return new PartitionResult<T>(partitionId, JobStatus.SKIPPED,
                                    0, 0, List.of("Skipped due to fail-fast"), Duration.ZERO, null);
                        }

                        Instant partStart = Instant.now();
                        AtomicInteger progressCounter = new AtomicInteger(0);

                        try {
                            PartitionResult<T> result = job.processor().process(
                                    partitionId, businessDate, resumeFrom,
                                    progress -> {
                                        /* Checkpoint periodically */
                                        int count = progressCounter.incrementAndGet();
                                        if (count % job.checkpointInterval() == 0) {
                                            cpStore.save(new Checkpoint(
                                                    jobName, partitionId,
                                                    progress.lastProcessedId(),
                                                    progress.itemsProcessed(),
                                                    Instant.now(), Map.of()));
                                        }

                                        /* Track global skip count for threshold */
                                        globalSkipCount.addAndGet(progress.itemsSkipped());
                                        if (globalSkipCount.get() > job.skipLimit()) {
                                            if (job.failFastOnCriticalError()) {
                                                failFastSignal.set(JobStatus.FAILED);
                                            }
                                        }
                                    });

                            /* Final checkpoint */
                            cpStore.save(new Checkpoint(jobName, partitionId,
                                    "completed", result.itemsProcessed(),
                                    Instant.now(), Map.of("status", result.status().name())));

                            return result;
                        } catch (Exception ex) {
                            Duration elapsed = Duration.between(partStart, Instant.now());
                            log.error("[BatchOrchestrator] Partition {} of job [{}] failed: {}",
                                      partitionId, jobName, ex.getMessage(), ex);

                            if (job.failFastOnCriticalError()) {
                                failFastSignal.set(JobStatus.FAILED);
                            }

                            return new PartitionResult<T>(partitionId, JobStatus.FAILED,
                                    progressCounter.get(), 0,
                                    List.of(ex.getMessage()), elapsed, null);
                        }
                    }, partitionExecutor);

            partitionFutures.add(future);
        }

        /* Wait for all partitions to complete */
        List<PartitionResult<?>> partitionResults = partitionFutures.stream()
                .map(CompletableFuture::join)
                .map(r -> (PartitionResult<?>) r)
                .collect(java.util.stream.Collectors.toList());

        Instant completedAt = Instant.now();
        Duration totalElapsed = Duration.between(startedAt, completedAt);

        /* Aggregate results */
        long totalProcessed = 0, totalSkipped = 0, totalFailed = 0;
        var allErrors = new ArrayList<String>();
        boolean anyFailed = false;
        boolean allCompleted = true;

        for (PartitionResult<?> pr : partitionResults) {
            totalProcessed += pr.itemsProcessed();
            totalSkipped += pr.itemsSkipped();
            allErrors.addAll(pr.errors());
            if (pr.status() == JobStatus.FAILED) {
                anyFailed = true;
                totalFailed++;
            }
            if (pr.status() != JobStatus.COMPLETED) {
                allCompleted = false;
            }
        }

        JobStatus finalStatus;
        if (allCompleted) {
            finalStatus = JobStatus.COMPLETED;
            cpStore.clear(jobName); // clean up checkpoints on success
        } else if (anyFailed && job.compensator() != null) {
            finalStatus = JobStatus.PARTIALLY_COMPLETED;
        } else if (anyFailed) {
            finalStatus = JobStatus.FAILED;
        } else {
            finalStatus = JobStatus.PARTIALLY_COMPLETED;
        }

        var result = new JobExecutionResult(
                jobName, finalStatus, businessDate,
                totalProcessed, totalSkipped, totalFailed,
                totalElapsed, partitionResults, allErrors,
                startedAt, completedAt);

        /* Run compensation if needed */
        if (finalStatus == JobStatus.PARTIALLY_COMPLETED && job.compensator() != null) {
            log.warn("[BatchOrchestrator] Running compensation for partially completed job [{}]",
                     jobName);
            try {
                job.compensator().accept(result);
            } catch (Exception ex) {
                log.error("[BatchOrchestrator] Compensation for job [{}] failed: {}",
                          jobName, ex.getMessage(), ex);
            }
        }

        log.info("[BatchOrchestrator] Job [{}] finished: status={}, processed={}, " +
                 "skipped={}, failed={}, elapsed={}",
                 jobName, finalStatus, totalProcessed, totalSkipped, totalFailed, totalElapsed);

        return result;
    }

    /**
     * Executes all registered jobs respecting their dependency graph.
     * Uses topological ordering to determine execution sequence; jobs
     * with no unmet dependencies run in parallel.
     */
    public List<JobExecutionResult> executeAll(LocalDate businessDate) {
        List<String> order = topologicalSort();
        log.info("[BatchOrchestrator] Executing {} jobs in dependency order: {}",
                 order.size(), order);

        var results = new ArrayList<JobExecutionResult>();
        var completed = ConcurrentHashMap.newKeySet();

        for (String jobName : order) {
            var job = registeredJobs.get(jobName);
            if (job == null) continue;

            /* Verify all dependencies completed successfully */
            boolean depsOk = job.dependencies().stream().allMatch(completed::contains);
            if (!depsOk) {
                log.warn("[BatchOrchestrator] Skipping job [{}] — unmet dependencies", jobName);
                continue;
            }

            var result = executeJob(jobName, businessDate);
            results.add(result);

            if (result.status() == JobStatus.COMPLETED) {
                completed.add(jobName);
            }
        }

        return Collections.unmodifiableList(results);
    }

    // -------------------------------------------------------------------
    //  Topological sort
    // -------------------------------------------------------------------

    private List<String> topologicalSort() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (var entry : registeredJobs.entrySet()) {
            String name = entry.getKey();
            graph.putIfAbsent(name, ConcurrentHashMap.newKeySet());
            inDegree.putIfAbsent(name, 0);

            for (String dep : entry.getValue().dependencies()) {
                graph.computeIfAbsent(dep, k -> ConcurrentHashMap.newKeySet()).add(name);
                inDegree.merge(name, 1, Integer::sum);
                inDegree.putIfAbsent(dep, 0);
            }
        }

        var queue = new ArrayList<String>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        var sorted = new ArrayList<String>();
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            sorted.add(node);
            for (String neighbor : graph.getOrDefault(node, Set.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (sorted.size() < registeredJobs.size()) {
            log.error("[BatchOrchestrator] Circular dependency detected in job graph!");
        }

        return sorted;
    }
}
