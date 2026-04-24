package com.omnibank.integration.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.omnibank.shared.resilience.BulkheadConfig;
import com.omnibank.shared.resilience.BulkheadRegistry;
import com.omnibank.shared.resilience.CircuitBreakerRegistry;
import com.omnibank.shared.resilience.FallbackStrategy;
import com.omnibank.shared.resilience.RateLimiterRegistry;
import com.omnibank.shared.resilience.ResilienceChain;
import com.omnibank.shared.resilience.RetryPolicy;
import com.omnibank.shared.resilience.TimeLimiter;

/**
 * Gateway to correspondent banks for wire settlement over the SWIFT
 * network. Each correspondent bank has its own circuit breaker, bulkhead,
 * and retry configuration, because different banks have vastly different
 * reliability and latency profiles.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Format outgoing MT103 / MT202 SWIFT messages</li>
 *   <li>Submit via SWIFT Alliance Lite2 or equivalent API</li>
 *   <li>Handle ACK / NACK acknowledgements with retry on NACK</li>
 *   <li>Per-correspondent circuit breaker and bulkhead isolation</li>
 *   <li>Per-message-type bulkhead (high-value wires vs standard)</li>
 * </ul>
 */
public class CorrespondentBankGateway {

    private static final Logger log = LoggerFactory.getLogger(CorrespondentBankGateway.class);

    private final ExternalServiceClient serviceClient;
    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final RateLimiterRegistry rlRegistry;
    private final TimeLimiter timeLimiter;

    /** Per-correspondent resilience chains, lazily initialised. */
    private final ConcurrentHashMap<String, ResilienceChain<SwiftResponse>> correspondentChains =
            new ConcurrentHashMap<>();

    /** Per-message-type bulkheads. */
    private final ConcurrentHashMap<String, BulkheadRegistry.ManagedBulkhead> messageTypeBulkheads =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Domain types
    // -------------------------------------------------------------------

    /**
     * @param bicCode           correspondent bank BIC (e.g. "CHASUS33")
     * @param swiftEndpoint     API endpoint for SWIFT message submission
     * @param signingKeyId      HMAC key ID
     * @param signingSecret     HMAC secret
     * @param maxConcurrent     concurrency limit for this correspondent
     * @param timeoutSeconds    per-message timeout
     */
    public record CorrespondentConfig(
            String bicCode,
            String swiftEndpoint,
            String signingKeyId,
            String signingSecret,
            int maxConcurrent,
            int timeoutSeconds
    ) {
        public CorrespondentConfig {
            Objects.requireNonNull(bicCode, "bicCode");
            Objects.requireNonNull(swiftEndpoint, "swiftEndpoint");
        }
    }

    /**
     * SWIFT message types relevant to wire settlement.
     */
    public enum SwiftMessageType {
        MT103("MT103", "Customer Transfer"),
        MT202("MT202", "Bank-to-Bank Transfer"),
        MT199("MT199", "Free Format Message"),
        MT900("MT900", "Confirmation of Debit"),
        MT910("MT910", "Confirmation of Credit");

        private final String code;
        private final String description;

        SwiftMessageType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String code()        { return code; }
        public String description() { return description; }
    }

    /**
     * Wire transfer request.
     */
    public record WireSettlementRequest(
            String transactionRef,
            String senderBic,
            String receiverBic,
            String beneficiaryAccount,
            String beneficiaryName,
            java.math.BigDecimal amount,
            String currency,
            LocalDate valueDate,
            SwiftMessageType messageType,
            String remittanceInfo,
            String idempotencyKey
    ) {
        public WireSettlementRequest {
            Objects.requireNonNull(transactionRef, "transactionRef");
            Objects.requireNonNull(senderBic, "senderBic");
            Objects.requireNonNull(receiverBic, "receiverBic");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(messageType, "messageType");
            if (idempotencyKey == null) {
                idempotencyKey = UUID.randomUUID().toString();
            }
        }
    }

    /**
     * Response from the SWIFT network or correspondent.
     */
    public record SwiftResponse(
            String uetr,
            AckStatus status,
            String rawMessage,
            Instant timestamp,
            String correspondentBic,
            Map<String, String> metadata
    ) {
        public enum AckStatus { ACK, NACK, PENDING, TIMEOUT }
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public CorrespondentBankGateway(ExternalServiceClient serviceClient,
                                    CircuitBreakerRegistry cbRegistry,
                                    BulkheadRegistry bhRegistry,
                                    RateLimiterRegistry rlRegistry,
                                    TimeLimiter timeLimiter) {
        this.serviceClient = Objects.requireNonNull(serviceClient);
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.rlRegistry = Objects.requireNonNull(rlRegistry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);
    }

    // -------------------------------------------------------------------
    //  Wire settlement
    // -------------------------------------------------------------------

    /**
     * Submits a wire transfer to the correspondent bank. The call goes
     * through a per-correspondent resilience chain and a per-message-type
     * bulkhead.
     */
    public SwiftResponse submitWireTransfer(CorrespondentConfig correspondent,
                                            WireSettlementRequest request) {
        log.info("[CorrespondentGateway] Submitting {} to {} | ref={} | amount={} {}",
                 request.messageType().code(), correspondent.bicCode(),
                 request.transactionRef(), request.amount(), request.currency());

        /* Acquire message-type bulkhead first */
        var msgBulkhead = getOrCreateMessageTypeBulkhead(request.messageType());

        return msgBulkhead.execute(() -> {
            /* Then go through per-correspondent resilience chain */
            var chain = getOrCreateCorrespondentChain(correspondent);
            return chain.execute(() -> sendSwiftMessage(correspondent, request));
        });
    }

    /**
     * Asynchronous submission that returns a future.
     */
    public CompletableFuture<SwiftResponse> submitWireTransferAsync(
            CorrespondentConfig correspondent, WireSettlementRequest request) {
        // JDK 17 cross-compat: platform threads via newCachedThreadPool.
        // Virtual threads (JEP 444) were finalized in JDK 21; a cached
        // pool is the closest drop-in on 17 for a short-lived async
        // submission.
        return CompletableFuture.supplyAsync(
                () -> submitWireTransfer(correspondent, request),
                java.util.concurrent.Executors.newCachedThreadPool());
    }

    /**
     * Polls for acknowledgement status of a previously submitted message.
     */
    public SwiftResponse checkAcknowledgement(CorrespondentConfig correspondent,
                                               String uetr) {
        var chain = getOrCreateCorrespondentChain(correspondent);
        return chain.execute(() -> pollAckStatus(correspondent, uetr));
    }

    // -------------------------------------------------------------------
    //  SWIFT message formatting
    // -------------------------------------------------------------------

    /**
     * Formats a wire settlement request into a SWIFT MT message body.
     */
    String formatSwiftMessage(WireSettlementRequest request) {
        return switch (request.messageType()) {
            case MT103 -> formatMT103(request);
            case MT202 -> formatMT202(request);
            default -> formatGenericMT(request);
        };
    }

    private String formatMT103(WireSettlementRequest request) {
        return """
                {1:F01%sXXXX0000000000}
                {2:I103%sXXXXN}
                {4:
                :20:%s
                :23B:CRED
                :32A:%s%s%s
                :50K:/%s
                :59:/%s
                %s
                :71A:SHA
                -}""".formatted(
                request.senderBic(),
                request.receiverBic(),
                request.transactionRef(),
                request.valueDate().toString().replace("-", ""),
                request.currency(),
                request.amount().toPlainString(),
                request.senderBic(),
                request.beneficiaryAccount(),
                request.beneficiaryName());
    }

    private String formatMT202(WireSettlementRequest request) {
        return """
                {1:F01%sXXXX0000000000}
                {2:I202%sXXXXN}
                {4:
                :20:%s
                :21:NONREF
                :32A:%s%s%s
                :58A:%s
                -}""".formatted(
                request.senderBic(),
                request.receiverBic(),
                request.transactionRef(),
                request.valueDate().toString().replace("-", ""),
                request.currency(),
                request.amount().toPlainString(),
                request.receiverBic());
    }

    private String formatGenericMT(WireSettlementRequest request) {
        return """
                {"msgType":"%s","ref":"%s","sender":"%s","receiver":"%s",\
                "amount":"%s","currency":"%s","valueDate":"%s"}""".formatted(
                request.messageType().code(),
                request.transactionRef(),
                request.senderBic(),
                request.receiverBic(),
                request.amount().toPlainString(),
                request.currency(),
                request.valueDate());
    }

    // -------------------------------------------------------------------
    //  Internal execution
    // -------------------------------------------------------------------

    private SwiftResponse sendSwiftMessage(CorrespondentConfig correspondent,
                                           WireSettlementRequest request) {
        String messageBody = formatSwiftMessage(request);
        String uetr = UUID.randomUUID().toString();

        var svcConfig = ExternalServiceClient.ServiceConfig.swiftDefaults(
                "swift-" + correspondent.bicCode(),
                correspondent.swiftEndpoint(),
                correspondent.signingKeyId(),
                correspondent.signingSecret());

        var svcChain = serviceClient.buildResilienceChain(svcConfig);

        String jsonPayload = """
                {"uetr":"%s","idempotencyKey":"%s","message":%s}"""
                .formatted(uetr, request.idempotencyKey(),
                           "\"" + messageBody.replace("\"", "\\\"") + "\"");

        ExternalServiceClient.ServiceResponse httpResponse =
                serviceClient.send(svcConfig, svcChain, "POST", "/swift/submit", jsonPayload);

        if (httpResponse.isSuccess()) {
            return new SwiftResponse(uetr, SwiftResponse.AckStatus.PENDING,
                    httpResponse.body(), Instant.now(), correspondent.bicCode(),
                    Map.of("httpStatus", String.valueOf(httpResponse.statusCode())));
        }

        throw new SwiftSubmissionException(correspondent.bicCode(),
                "HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
    }

    private SwiftResponse pollAckStatus(CorrespondentConfig correspondent, String uetr) {
        var svcConfig = ExternalServiceClient.ServiceConfig.swiftDefaults(
                "swift-ack-" + correspondent.bicCode(),
                correspondent.swiftEndpoint(),
                correspondent.signingKeyId(),
                correspondent.signingSecret());
        var svcChain = serviceClient.buildResilienceChain(svcConfig);

        ExternalServiceClient.ServiceResponse httpResponse =
                serviceClient.send(svcConfig, svcChain, "GET", "/swift/status/" + uetr, null);

        /* Parse ACK / NACK from response body (simplified) */
        SwiftResponse.AckStatus status = httpResponse.body().contains("\"ACK\"")
                ? SwiftResponse.AckStatus.ACK
                : httpResponse.body().contains("\"NACK\"")
                        ? SwiftResponse.AckStatus.NACK
                        : SwiftResponse.AckStatus.PENDING;

        return new SwiftResponse(uetr, status, httpResponse.body(),
                Instant.now(), correspondent.bicCode(), Map.of());
    }

    // -------------------------------------------------------------------
    //  Resilience chain factories
    // -------------------------------------------------------------------

    private ResilienceChain<SwiftResponse> getOrCreateCorrespondentChain(
            CorrespondentConfig config) {
        return correspondentChains.computeIfAbsent(config.bicCode(), bic -> {
            log.info("[CorrespondentGateway] Creating resilience chain for correspondent {}",
                     bic);

            CircuitBreakerRegistry.ManagedCircuitBreaker<SwiftResponse> cb =
                    cbRegistry.getOrCreate("correspondent-" + bic,
                            CircuitBreakerRegistry.Config.defaults()
                                    .withFailureThreshold(3)
                                    .withResetTimeout(Duration.ofMinutes(2)));

            var bh = bhRegistry.getOrCreate(BulkheadConfig.semaphore(
                    "correspondent-" + bic, config.maxConcurrent()));

            var retry = RetryPolicy.builder("correspondent-" + bic)
                    .maxAttempts(3)
                    .backoff(RetryPolicy.exponential(
                            Duration.ofSeconds(1), Duration.ofSeconds(15), 2.0))
                    .retryOn(ex -> ex instanceof ExternalServiceClient.TransientServiceException
                                || ex instanceof java.net.SocketTimeoutException)
                    .build();

            return ResilienceChain.<SwiftResponse>builder("correspondent-" + bic)
                    .circuitBreaker(cb)
                    .bulkhead(bh)
                    .retryPolicy(retry)
                    .timeLimiter(timeLimiter, Duration.ofSeconds(config.timeoutSeconds()))
                    .fallback(new FallbackStrategy.FailFastFallback<>("correspondent-" + bic))
                    .build();
        });
    }

    private BulkheadRegistry.ManagedBulkhead getOrCreateMessageTypeBulkhead(
            SwiftMessageType type) {
        return messageTypeBulkheads.computeIfAbsent(type.code(), code -> {
            int maxConcurrent = switch (type) {
                case MT103 -> 50;   // high volume customer transfers
                case MT202 -> 30;   // bank-to-bank, lower volume
                case MT199 -> 10;   // free format, rare
                case MT900, MT910 -> 20; // confirmations
            };
            return bhRegistry.getOrCreate(BulkheadConfig.semaphore(
                    "swift-msgtype-" + code, maxConcurrent));
        });
    }

    // -------------------------------------------------------------------
    //  Exceptions
    // -------------------------------------------------------------------

    public static final class SwiftSubmissionException extends RuntimeException {
        private final String correspondentBic;

        public SwiftSubmissionException(String correspondentBic, String detail) {
            super("SWIFT submission failed for [%s]: %s".formatted(correspondentBic, detail));
            this.correspondentBic = correspondentBic;
        }

        public String correspondentBic() { return correspondentBic; }
    }

    // -------------------------------------------------------------------
    //  JSON escape helper (inner static class to avoid external dependency)
    // -------------------------------------------------------------------

    static final class JsonEscape {
        static String quote(String raw) {
            if (raw == null) return "null";
            return "\"" + raw.replace("\\", "\\\\")
                             .replace("\"", "\\\"")
                             .replace("\n", "\\n")
                             .replace("\r", "\\r")
                             .replace("\t", "\\t") + "\"";
        }
    }
}
