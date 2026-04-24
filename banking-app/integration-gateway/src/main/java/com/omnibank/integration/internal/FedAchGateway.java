package com.omnibank.integration.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.omnibank.shared.resilience.BulkheadConfig;
import com.omnibank.shared.resilience.BulkheadRegistry;
import com.omnibank.shared.resilience.CircuitBreakerRegistry;
import com.omnibank.shared.resilience.FallbackStrategy;
import com.omnibank.shared.resilience.ResilienceChain;
import com.omnibank.shared.resilience.RetryPolicy;
import com.omnibank.shared.resilience.TimeLimiter;

/**
 * Gateway to the Federal Reserve ACH (Automated Clearing House) network.
 * Handles batch file submission, acknowledgement polling, and return file
 * processing. Each operation class (submission vs retrieval) has its own
 * circuit breaker because the Fed can accept submissions while the retrieval
 * endpoint is under maintenance, and vice versa.
 *
 * <p>Key resilience patterns:
 * <ul>
 *   <li>Separate circuit breakers for submission and retrieval operations</li>
 *   <li>File-level retry with idempotency keys to prevent duplicate submissions</li>
 *   <li>Bulkhead limiting concurrent ACH connections (Fed has connection caps)</li>
 *   <li>Timeout tuned to Fed processing windows</li>
 * </ul>
 */
public class FedAchGateway {

    private static final Logger log = LoggerFactory.getLogger(FedAchGateway.class);

    private static final DateTimeFormatter ACH_DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final Duration SUBMISSION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RETRIEVAL_TIMEOUT = Duration.ofSeconds(30);

    private final ExternalServiceClient serviceClient;
    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final TimeLimiter timeLimiter;

    /** Separate resilience chains for submission vs retrieval. */
    private volatile ResilienceChain<AchSubmissionResult> submissionChain;
    private volatile ResilienceChain<AchRetrievalResult> retrievalChain;

    /** Tracks in-flight idempotency keys to prevent duplicate submissions. */
    private final ConcurrentHashMap<String, Instant> idempotencyTracker = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Domain types
    // -------------------------------------------------------------------

    /**
     * ACH batch file for submission to the Fed.
     *
     * @param batchId            unique batch identifier
     * @param originRoutingNumber  originator's ABA routing number
     * @param settlementDate     effective settlement date
     * @param entries            individual ACH entries in the batch
     * @param idempotencyKey     client-generated key to prevent duplicate submission
     * @param totalDebitAmount   sum of all debit entries (in cents)
     * @param totalCreditAmount  sum of all credit entries (in cents)
     */
    public record AchBatchFile(
            String batchId,
            String originRoutingNumber,
            LocalDate settlementDate,
            List<AchEntry> entries,
            String idempotencyKey,
            long totalDebitAmount,
            long totalCreditAmount
    ) {
        public AchBatchFile {
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(originRoutingNumber, "originRoutingNumber");
            Objects.requireNonNull(settlementDate, "settlementDate");
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty()) throw new IllegalArgumentException("Batch must have at least one entry");
            if (idempotencyKey == null) {
                idempotencyKey = UUID.randomUUID().toString();
            }
        }
    }

    /**
     * Individual ACH entry within a batch.
     */
    public record AchEntry(
            String traceNumber,
            String receivingRoutingNumber,
            String receivingAccountNumber,
            long amount,
            AchTransactionCode transactionCode,
            String individualName,
            String individualId,
            String addendaInfo
    ) {
        public AchEntry {
            Objects.requireNonNull(traceNumber, "traceNumber");
            Objects.requireNonNull(receivingRoutingNumber, "receivingRoutingNumber");
            Objects.requireNonNull(receivingAccountNumber, "receivingAccountNumber");
            Objects.requireNonNull(transactionCode, "transactionCode");
        }
    }

    public enum AchTransactionCode {
        CHECKING_CREDIT("22"),
        CHECKING_DEBIT("27"),
        SAVINGS_CREDIT("32"),
        SAVINGS_DEBIT("37"),
        CHECKING_CREDIT_PRENOTE("23"),
        CHECKING_DEBIT_PRENOTE("28");

        private final String code;

        AchTransactionCode(String code) { this.code = code; }
        public String code() { return code; }
    }

    /**
     * Result of a batch file submission.
     */
    public record AchSubmissionResult(
            String batchId,
            String fedFileId,
            SubmissionStatus status,
            Instant submittedAt,
            String message,
            Map<String, String> fedHeaders
    ) {
        public enum SubmissionStatus { ACCEPTED, REJECTED, PENDING }
    }

    /**
     * Result of an acknowledgement or return file retrieval.
     */
    public record AchRetrievalResult(
            String fileId,
            RetrievalType type,
            List<AchReturnEntry> returns,
            Instant retrievedAt,
            int totalEntries
    ) {
        public enum RetrievalType { ACKNOWLEDGEMENT, RETURN_FILE, NOTIFICATION_OF_CHANGE }
    }

    /**
     * Individual return entry within a return file.
     */
    public record AchReturnEntry(
            String originalTraceNumber,
            String returnReasonCode,
            String returnReasonDescription,
            long originalAmount,
            String originatingRoutingNumber
    ) {}

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    public record FedAchConfig(
            String fedEndpoint,
            String signingKeyId,
            String signingSecret,
            int maxConcurrentSubmissions,
            int maxConcurrentRetrievals,
            int submissionRetries,
            int retrievalRetries
    ) {
        public FedAchConfig {
            Objects.requireNonNull(fedEndpoint, "fedEndpoint");
            if (maxConcurrentSubmissions <= 0) throw new IllegalArgumentException("maxConcurrentSubmissions must be > 0");
            if (maxConcurrentRetrievals <= 0) throw new IllegalArgumentException("maxConcurrentRetrievals must be > 0");
        }

        public static FedAchConfig defaults(String endpoint, String keyId, String secret) {
            return new FedAchConfig(endpoint, keyId, secret, 10, 8, 3, 4);
        }
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public FedAchGateway(ExternalServiceClient serviceClient,
                          CircuitBreakerRegistry cbRegistry,
                          BulkheadRegistry bhRegistry,
                          TimeLimiter timeLimiter) {
        this.serviceClient = Objects.requireNonNull(serviceClient);
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);
    }

    // -------------------------------------------------------------------
    //  Initialisation
    // -------------------------------------------------------------------

    /**
     * Initialises the submission and retrieval resilience chains. Called
     * once at application startup after configuration is resolved.
     */
    public void initialize(FedAchConfig config) {
        this.submissionChain = buildSubmissionChain(config);
        this.retrievalChain = buildRetrievalChain(config);
        log.info("[FedAchGateway] Initialised with endpoint={}, " +
                 "maxSubmissions={}, maxRetrievals={}",
                 config.fedEndpoint(), config.maxConcurrentSubmissions(),
                 config.maxConcurrentRetrievals());
    }

    // -------------------------------------------------------------------
    //  Batch file submission
    // -------------------------------------------------------------------

    /**
     * Submits an ACH batch file to the Federal Reserve. Uses file-level
     * idempotency to prevent duplicate submissions on retry.
     */
    public AchSubmissionResult submitBatch(FedAchConfig config, AchBatchFile batchFile) {
        ensureInitialised();

        /* Idempotency check */
        Instant existing = idempotencyTracker.putIfAbsent(
                batchFile.idempotencyKey(), Instant.now());
        if (existing != null) {
            log.warn("[FedAchGateway] Duplicate submission detected for idempotencyKey={}, " +
                     "original submission at {}", batchFile.idempotencyKey(), existing);
            return new AchSubmissionResult(
                    batchFile.batchId(), null,
                    AchSubmissionResult.SubmissionStatus.REJECTED,
                    Instant.now(), "Duplicate submission blocked by idempotency check",
                    Map.of());
        }

        log.info("[FedAchGateway] Submitting ACH batch: batchId={}, entries={}, " +
                 "settlementDate={}, totalDebit={}, totalCredit={}",
                 batchFile.batchId(), batchFile.entries().size(),
                 batchFile.settlementDate(), batchFile.totalDebitAmount(),
                 batchFile.totalCreditAmount());

        try {
            return submissionChain.execute(() -> doSubmitBatch(config, batchFile));
        } catch (Exception ex) {
            /* Remove idempotency key on failure so the batch can be retried */
            idempotencyTracker.remove(batchFile.idempotencyKey());
            throw ex;
        }
    }

    // -------------------------------------------------------------------
    //  Acknowledgement polling
    // -------------------------------------------------------------------

    /**
     * Polls the Fed for acknowledgement of a previously submitted batch.
     */
    public AchRetrievalResult pollAcknowledgement(FedAchConfig config, String fedFileId) {
        ensureInitialised();
        log.debug("[FedAchGateway] Polling acknowledgement for fedFileId={}", fedFileId);
        return retrievalChain.execute(() -> doPollAck(config, fedFileId));
    }

    // -------------------------------------------------------------------
    //  Return file processing
    // -------------------------------------------------------------------

    /**
     * Retrieves and processes return files for a given settlement date.
     * Return files contain entries that the receiving bank rejected
     * (insufficient funds, account closed, etc.).
     */
    public AchRetrievalResult retrieveReturnFiles(FedAchConfig config,
                                                    LocalDate settlementDate) {
        ensureInitialised();
        log.info("[FedAchGateway] Retrieving return files for settlementDate={}",
                 settlementDate);
        return retrievalChain.execute(() -> doRetrieveReturns(config, settlementDate));
    }

    // -------------------------------------------------------------------
    //  Internal HTTP calls
    // -------------------------------------------------------------------

    private AchSubmissionResult doSubmitBatch(FedAchConfig config, AchBatchFile batch) {
        String nachaContent = formatNachaFile(batch);
        String payload = """
                {"batchId":"%s","idempotencyKey":"%s","settlementDate":"%s",\
                "entryCount":%d,"nachaFile":"%s"}"""
                .formatted(batch.batchId(), batch.idempotencyKey(),
                           batch.settlementDate(),
                           batch.entries().size(),
                           nachaContent.replace("\"", "\\\"").replace("\n", "\\n"));

        var svcConfig = ExternalServiceClient.ServiceConfig.fedDefaults(
                "fedach-submit", config.fedEndpoint(),
                config.signingKeyId(), config.signingSecret());
        var svcChain = serviceClient.buildResilienceChain(svcConfig);

        ExternalServiceClient.ServiceResponse response =
                serviceClient.send(svcConfig, svcChain, "POST", "/ach/submit", payload);

        var status = response.isSuccess()
                ? AchSubmissionResult.SubmissionStatus.ACCEPTED
                : AchSubmissionResult.SubmissionStatus.REJECTED;

        return new AchSubmissionResult(
                batch.batchId(),
                response.headers().getOrDefault("X-Fed-File-Id", ""),
                status, Instant.now(), response.body(),
                response.headers());
    }

    private AchRetrievalResult doPollAck(FedAchConfig config, String fedFileId) {
        var svcConfig = ExternalServiceClient.ServiceConfig.fedDefaults(
                "fedach-ack", config.fedEndpoint(),
                config.signingKeyId(), config.signingSecret());
        var svcChain = serviceClient.buildResilienceChain(svcConfig);

        ExternalServiceClient.ServiceResponse response =
                serviceClient.send(svcConfig, svcChain, "GET",
                        "/ach/acknowledgement/" + fedFileId, null);

        return new AchRetrievalResult(
                fedFileId, AchRetrievalResult.RetrievalType.ACKNOWLEDGEMENT,
                List.of(), Instant.now(), 0);
    }

    private AchRetrievalResult doRetrieveReturns(FedAchConfig config,
                                                  LocalDate settlementDate) {
        var svcConfig = ExternalServiceClient.ServiceConfig.fedDefaults(
                "fedach-returns", config.fedEndpoint(),
                config.signingKeyId(), config.signingSecret());
        var svcChain = serviceClient.buildResilienceChain(svcConfig);

        String datePath = settlementDate.format(ACH_DATE_FMT);
        ExternalServiceClient.ServiceResponse response =
                serviceClient.send(svcConfig, svcChain, "GET",
                        "/ach/returns/" + datePath, null);

        /* In production, parse the NACHA return file. Simplified here. */
        return new AchRetrievalResult(
                "return-" + datePath,
                AchRetrievalResult.RetrievalType.RETURN_FILE,
                List.of(), Instant.now(), 0);
    }

    // -------------------------------------------------------------------
    //  NACHA file formatting (simplified)
    // -------------------------------------------------------------------

    private String formatNachaFile(AchBatchFile batch) {
        var sb = new StringBuilder(2048);

        /* File Header Record (Record Type 1) */
        sb.append("101 %s %s%s0000A094101 OMNIBANK                FEDERAL RESERVE   \n"
                .formatted(batch.originRoutingNumber(),
                           batch.originRoutingNumber(),
                           batch.settlementDate().format(ACH_DATE_FMT)));

        /* Batch Header Record (Record Type 5) */
        sb.append("5200OMNIBANK        ACH PAYMENTS     %s PPD PAYROLL   %s   1%s0000001\n"
                .formatted(batch.originRoutingNumber(),
                           batch.settlementDate().format(ACH_DATE_FMT),
                           batch.originRoutingNumber()));

        /* Entry Detail Records (Record Type 6) */
        for (AchEntry entry : batch.entries()) {
            sb.append("6%s%s%s%010d%s%-22s  0%s%s\n"
                    .formatted(entry.transactionCode().code(),
                               entry.receivingRoutingNumber(),
                               entry.receivingAccountNumber(),
                               entry.amount(),
                               entry.individualId() != null ? entry.individualId() : "               ",
                               entry.individualName() != null ? entry.individualName() : "",
                               batch.originRoutingNumber(),
                               entry.traceNumber()));
        }

        /* Batch Control Record (Record Type 8) */
        sb.append("8200%06d%012d%012d%s                         %s0000001\n"
                .formatted(batch.entries().size(),
                           batch.totalDebitAmount(),
                           batch.totalCreditAmount(),
                           batch.originRoutingNumber(),
                           batch.originRoutingNumber()));

        /* File Control Record (Record Type 9) */
        sb.append("9000001000001%08d%012d%012d                                       \n"
                .formatted(batch.entries().size(),
                           batch.totalDebitAmount(),
                           batch.totalCreditAmount()));

        return sb.toString();
    }

    // -------------------------------------------------------------------
    //  Resilience chain factories
    // -------------------------------------------------------------------

    private ResilienceChain<AchSubmissionResult> buildSubmissionChain(FedAchConfig config) {
        CircuitBreakerRegistry.ManagedCircuitBreaker<AchSubmissionResult> cb =
                cbRegistry.getOrCreate("fedach-submission",
                        CircuitBreakerRegistry.Config.defaults()
                                .withFailureThreshold(3)
                                .withResetTimeout(Duration.ofMinutes(5)));

        var bh = bhRegistry.getOrCreate(BulkheadConfig.semaphore(
                "fedach-submission", config.maxConcurrentSubmissions()));

        var retry = RetryPolicy.builder("fedach-submission")
                .maxAttempts(config.submissionRetries())
                .backoff(RetryPolicy.exponential(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 2.0))
                .jitter(0.25)
                .retryOn(ex -> ex instanceof ExternalServiceClient.TransientServiceException)
                .build();

        return ResilienceChain.<AchSubmissionResult>builder("fedach-submission")
                .circuitBreaker(cb)
                .bulkhead(bh)
                .retryPolicy(retry)
                .timeLimiter(timeLimiter, SUBMISSION_TIMEOUT)
                .fallback(new FallbackStrategy.FailFastFallback<>("fedach-submission"))
                .build();
    }

    private ResilienceChain<AchRetrievalResult> buildRetrievalChain(FedAchConfig config) {
        CircuitBreakerRegistry.ManagedCircuitBreaker<AchRetrievalResult> cb =
                cbRegistry.getOrCreate("fedach-retrieval",
                        CircuitBreakerRegistry.Config.defaults()
                                .withFailureThreshold(5)
                                .withResetTimeout(Duration.ofMinutes(3)));

        var bh = bhRegistry.getOrCreate(BulkheadConfig.semaphore(
                "fedach-retrieval", config.maxConcurrentRetrievals()));

        var retry = RetryPolicy.builder("fedach-retrieval")
                .maxAttempts(config.retrievalRetries())
                .backoff(RetryPolicy.exponential(
                        Duration.ofSeconds(1), Duration.ofSeconds(15), 2.0))
                .jitter(0.2)
                .retryOn(ex -> ex instanceof ExternalServiceClient.TransientServiceException)
                .build();

        return ResilienceChain.<AchRetrievalResult>builder("fedach-retrieval")
                .circuitBreaker(cb)
                .bulkhead(bh)
                .retryPolicy(retry)
                .timeLimiter(timeLimiter, RETRIEVAL_TIMEOUT)
                .fallback(new FallbackStrategy.FailFastFallback<>("fedach-retrieval"))
                .build();
    }

    // -------------------------------------------------------------------
    //  Validation
    // -------------------------------------------------------------------

    private void ensureInitialised() {
        if (submissionChain == null || retrievalChain == null) {
            throw new IllegalStateException(
                    "FedAchGateway has not been initialised. Call initialize() first.");
        }
    }
}
