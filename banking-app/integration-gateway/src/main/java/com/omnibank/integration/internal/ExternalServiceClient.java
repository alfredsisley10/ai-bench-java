package com.omnibank.integration.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.omnibank.shared.resilience.BulkheadConfig;
import com.omnibank.shared.resilience.BulkheadRegistry;
import com.omnibank.shared.resilience.CircuitBreakerRegistry;
import com.omnibank.shared.resilience.FallbackStrategy;
import com.omnibank.shared.resilience.RateLimiterRegistry;
import com.omnibank.shared.resilience.ResilienceChain;
import com.omnibank.shared.resilience.RetryPolicy;
import com.omnibank.shared.resilience.TimeLimiter;

/**
 * Base HTTP client for communicating with external financial services
 * (FedACH, Fedwire, correspondent banks, SWIFT). Integrates all
 * resilience components via {@link ResilienceChain}.
 *
 * <p>Features:
 * <ul>
 *   <li>Connection pooling with configurable keep-alive</li>
 *   <li>HMAC-SHA256 request signing for mutual authentication</li>
 *   <li>Circuit breaker per target service</li>
 *   <li>Retry with exponential backoff on transient HTTP errors</li>
 *   <li>Bulkhead isolation per service to prevent cascade failures</li>
 *   <li>Configurable timeout per request type</li>
 * </ul>
 */
public class ExternalServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceClient.class);

    private final HttpClient httpClient;
    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final RateLimiterRegistry rlRegistry;
    private final TimeLimiter timeLimiter;

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Per-service client configuration.
     *
     * @param serviceName         logical name for metrics and logging
     * @param baseUrl             base URL of the external service
     * @param connectTimeout      TCP connect timeout
     * @param requestTimeout      per-request wall-clock timeout
     * @param signingKeyId        HMAC key identifier for request signing
     * @param signingSecret       HMAC shared secret
     * @param maxConcurrentCalls  bulkhead concurrency limit
     * @param maxRetries          retry attempts (including initial call)
     * @param retryBaseDelay      base delay for exponential backoff
     * @param rateLimitPerSecond  calls per second (0 = unlimited)
     */
    public record ServiceConfig(
            String serviceName,
            String baseUrl,
            Duration connectTimeout,
            Duration requestTimeout,
            String signingKeyId,
            String signingSecret,
            int maxConcurrentCalls,
            int maxRetries,
            Duration retryBaseDelay,
            long rateLimitPerSecond
    ) {
        public ServiceConfig {
            Objects.requireNonNull(serviceName, "serviceName");
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(retryBaseDelay, "retryBaseDelay");
            if (maxConcurrentCalls <= 0) throw new IllegalArgumentException("maxConcurrentCalls must be > 0");
            if (maxRetries < 1) throw new IllegalArgumentException("maxRetries must be >= 1");
        }

        /** Sensible defaults for a Federal Reserve network endpoint. */
        public static ServiceConfig fedDefaults(String name, String baseUrl,
                                                 String keyId, String secret) {
            return new ServiceConfig(name, baseUrl,
                    Duration.ofSeconds(5), Duration.ofSeconds(30),
                    keyId, secret, 20, 3,
                    Duration.ofMillis(500), 100);
        }

        /** Defaults for a correspondent bank SWIFT gateway. */
        public static ServiceConfig swiftDefaults(String name, String baseUrl,
                                                   String keyId, String secret) {
            return new ServiceConfig(name, baseUrl,
                    Duration.ofSeconds(10), Duration.ofSeconds(60),
                    keyId, secret, 15, 4,
                    Duration.ofSeconds(1), 50);
        }
    }

    // -------------------------------------------------------------------
    //  Response wrapper
    // -------------------------------------------------------------------

    /**
     * Wrapper around the HTTP response that includes timing and metadata.
     *
     * @param statusCode   HTTP status code
     * @param body         response body as string
     * @param headers      response headers
     * @param latency      wall-clock latency of the call
     * @param serviceName  which service was called
     */
    public record ServiceResponse(
            int statusCode,
            String body,
            Map<String, String> headers,
            Duration latency,
            String serviceName
    ) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public boolean isRetryable() {
            return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
        }
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public ExternalServiceClient(CircuitBreakerRegistry cbRegistry,
                                  BulkheadRegistry bhRegistry,
                                  RateLimiterRegistry rlRegistry,
                                  TimeLimiter timeLimiter) {
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.rlRegistry = Objects.requireNonNull(rlRegistry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);

        // JDK 17 cross-compat: cached platform threads in place of
        // virtual threads (JEP 444, finalized in JDK 21).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newCachedThreadPool())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // -------------------------------------------------------------------
    //  Resilience chain factory
    // -------------------------------------------------------------------

    /**
     * Builds a full resilience chain for the given service configuration.
     * Called once per service at startup; the chain is then reused for
     * all calls to that service.
     */
    public ResilienceChain<ServiceResponse> buildResilienceChain(ServiceConfig config) {
        /* Circuit breaker */
        var cbConfig = CircuitBreakerRegistry.Config.defaults()
                .withFailureThreshold(config.maxRetries())
                .withResetTimeout(Duration.ofSeconds(45));
        CircuitBreakerRegistry.ManagedCircuitBreaker<ServiceResponse> cb =
                cbRegistry.getOrCreate(config.serviceName(), cbConfig);

        /* Bulkhead */
        var bhConfig = BulkheadConfig.semaphore(
                config.serviceName(), config.maxConcurrentCalls());
        var bh = bhRegistry.getOrCreate(bhConfig);

        /* Retry */
        var retry = RetryPolicy.builder(config.serviceName())
                .maxAttempts(config.maxRetries())
                .backoff(RetryPolicy.exponential(
                        config.retryBaseDelay(), Duration.ofSeconds(10), 2.0))
                .jitter(0.2)
                .retryOn(ex -> isTransient(ex))
                .build();

        /* Rate limiter (if configured) */
        RateLimiterRegistry.ManagedRateLimiter rl = null;
        if (config.rateLimitPerSecond() > 0) {
            var rlConfig = RateLimiterRegistry.Config.tokenBucket(
                    config.serviceName(), config.rateLimitPerSecond(),
                    Duration.ofSeconds(1), config.rateLimitPerSecond() / 5,
                    config.rateLimitPerSecond());
            rl = rlRegistry.getOrCreate(rlConfig);
        }

        var builder = ResilienceChain.<ServiceResponse>builder(config.serviceName())
                .circuitBreaker(cb)
                .bulkhead(bh)
                .retryPolicy(retry)
                .timeLimiter(timeLimiter, config.requestTimeout())
                .fallback(new FallbackStrategy.FailFastFallback<>(config.serviceName()));

        if (rl != null) {
            builder.rateLimiter(rl);
        }

        return builder.build();
    }

    // -------------------------------------------------------------------
    //  HTTP execution
    // -------------------------------------------------------------------

    /**
     * Sends a signed HTTP request through the resilience chain.
     */
    public ServiceResponse send(ServiceConfig config,
                                ResilienceChain<ServiceResponse> chain,
                                String method,
                                String path,
                                String requestBody) {
        return chain.execute(() -> doSend(config, method, path, requestBody));
    }

    /**
     * Async variant using virtual threads.
     */
    public CompletableFuture<ServiceResponse> sendAsync(ServiceConfig config,
                                                         ResilienceChain<ServiceResponse> chain,
                                                         String method,
                                                         String path,
                                                         String requestBody) {
        return chain.executeAsync(() -> doSend(config, method, path, requestBody));
    }

    private ServiceResponse doSend(ServiceConfig config, String method,
                                   String path, String requestBody) {
        Instant start = Instant.now();
        URI uri = URI.create(config.baseUrl() + path);
        String timestamp = Instant.now().toString();

        /* Build and sign the request */
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("X-Request-Timestamp", timestamp)
                .header("X-Service-Name", config.serviceName());

        if (config.signingKeyId() != null && config.signingSecret() != null) {
            String signature = computeHmacSignature(
                    config.signingSecret(), method, path, timestamp, requestBody);
            reqBuilder.header("X-Signature-KeyId", config.signingKeyId());
            reqBuilder.header("X-Signature", signature);
        }

        HttpRequest.BodyPublisher bodyPublisher = (requestBody != null && !requestBody.isEmpty())
                ? HttpRequest.BodyPublishers.ofString(requestBody)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest request = switch (method.toUpperCase()) {
            case "GET"    -> reqBuilder.GET().build();
            case "POST"   -> reqBuilder.POST(bodyPublisher).build();
            case "PUT"    -> reqBuilder.PUT(bodyPublisher).build();
            case "DELETE" -> reqBuilder.DELETE().build();
            default       -> reqBuilder.method(method, bodyPublisher).build();
        };

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            Duration latency = Duration.between(start, Instant.now());
            var headers = new java.util.LinkedHashMap<String, String>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k, v.get(0));
            });

            var serviceResponse = new ServiceResponse(
                    response.statusCode(), response.body(), Map.copyOf(headers),
                    latency, config.serviceName());

            if (serviceResponse.isRetryable()) {
                throw new TransientServiceException(config.serviceName(),
                        response.statusCode(), response.body());
            }

            if (!serviceResponse.isSuccess()) {
                log.warn("[ExternalServiceClient:{}] Non-success response: HTTP {} from {}{}",
                         config.serviceName(), response.statusCode(), config.baseUrl(), path);
            }

            return serviceResponse;
        } catch (TransientServiceException tse) {
            throw tse;
        } catch (java.io.IOException ex) {
            throw new TransientServiceException(config.serviceName(), 0,
                    "I/O error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call interrupted for " + config.serviceName(), ex);
        }
    }

    // -------------------------------------------------------------------
    //  Request signing (HMAC-SHA256)
    // -------------------------------------------------------------------

    private String computeHmacSignature(String secret, String method, String path,
                                        String timestamp, String body) {
        try {
            String payload = method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n"
                    + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            log.error("Failed to compute HMAC signature", ex);
            throw new RuntimeException("HMAC signing failed", ex);
        }
    }

    // -------------------------------------------------------------------
    //  Transient error detection
    // -------------------------------------------------------------------

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof TransientServiceException) return true;
        if (ex instanceof java.net.SocketTimeoutException) return true;
        if (ex instanceof java.net.ConnectException) return true;
        if (ex instanceof java.io.IOException) return true;
        return false;
    }

    // -------------------------------------------------------------------
    //  Exceptions
    // -------------------------------------------------------------------

    public static final class TransientServiceException extends RuntimeException {
        private final String serviceName;
        private final int statusCode;

        public TransientServiceException(String serviceName, int statusCode, String detail) {
            super("Transient error from [%s]: HTTP %d — %s"
                          .formatted(serviceName, statusCode, detail));
            this.serviceName = serviceName;
            this.statusCode = statusCode;
        }

        public String serviceName() { return serviceName; }
        public int statusCode()     { return statusCode; }
    }
}
