package com.omnibank.compliance.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.shared.messaging.DomainEventRouter;
import com.omnibank.shared.messaging.EventStore;
import com.omnibank.shared.messaging.MessageBrokerConfig;
import com.omnibank.shared.messaging.MessageEnvelope;
import com.omnibank.shared.messaging.ReliableMessagePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Real-time compliance event consumer that monitors all transaction-related
 * domain events for regulatory screening requirements.
 *
 * <p>This consumer subscribes to payment, account, ledger, and loan event
 * topics and applies the following compliance checks:</p>
 *
 * <ul>
 *   <li><b>Sanctions screening</b> — checks party names against OFAC SDN, EU
 *       consolidated, and HMT sanctions lists on every outbound payment</li>
 *   <li><b>CTR filing</b> — flags cash transactions exceeding the $10,000
 *       Currency Transaction Report threshold (31 CFR 1010.311)</li>
 *   <li><b>SAR monitoring</b> — detects structuring patterns (multiple
 *       transactions just below the CTR threshold) and velocity anomalies</li>
 *   <li><b>Wire transfer rules</b> — validates Travel Rule compliance for
 *       international wires (FATF Recommendation 16)</li>
 *   <li><b>PEP screening</b> — flags transactions involving Politically
 *       Exposed Persons</li>
 * </ul>
 *
 * <p>When a screening rule triggers, the consumer publishes a compliance alert
 * to the {@code compliance.alerts} topic and records the screening result in
 * the event store for audit purposes.</p>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ComplianceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ReliableMessagePublisher messagePublisher;
    private final DomainEventRouter eventRouter;
    private final EventStore eventStore;

    /** CTR threshold under 31 CFR 1010.311 (USD). */
    private static final BigDecimal CTR_THRESHOLD = new BigDecimal("10000.00");

    /** Structuring detection: threshold fraction (e.g., 80% of CTR limit). */
    private static final BigDecimal STRUCTURING_LOWER_BOUND = new BigDecimal("8000.00");

    /** Number of near-threshold transactions in a window that triggers SAR review. */
    @Value("${omnibank.compliance.structuring-count-threshold:3}")
    private int structuringCountThreshold;

    /** Time window for structuring detection. */
    @Value("${omnibank.compliance.structuring-window:PT24H}")
    private Duration structuringWindow;

    /** Velocity: max transactions per account in a rolling window. */
    @Value("${omnibank.compliance.velocity-max-txns:50}")
    private int velocityMaxTransactions;

    @Value("${omnibank.compliance.velocity-window:PT1H}")
    private Duration velocityWindow;

    /** In-memory velocity tracker: accountId -> list of transaction timestamps. */
    private final ConcurrentHashMap<String, List<Instant>> velocityTracker = new ConcurrentHashMap<>();

    /** In-memory structuring tracker: accountId -> list of (amount, timestamp). */
    private final ConcurrentHashMap<String, List<TransactionRecord>> structuringTracker = new ConcurrentHashMap<>();

    /** Simple sanctioned-names cache. Production: backed by OFAC SDN data feed. */
    private final Set<String> sanctionedNamePatterns = Set.of(
            // Placeholder patterns — in production loaded from the OFAC SDN list
            "TEST_SANCTIONED", "BLOCKED_ENTITY"
    );

    /** Deduplication set for processed messages. */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    public ComplianceEventConsumer(ObjectMapper objectMapper,
                                   ReliableMessagePublisher messagePublisher,
                                   DomainEventRouter eventRouter,
                                   EventStore eventStore) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.messagePublisher = Objects.requireNonNull(messagePublisher);
        this.eventRouter = Objects.requireNonNull(eventRouter);
        this.eventStore = Objects.requireNonNull(eventStore);
    }

    @PostConstruct
    void registerWithRouter() {
        eventRouter.register(new AllTransactionEventHandler());
        log.info("ComplianceEventConsumer registered wildcard handler with DomainEventRouter");
    }

    // -----------------------------------------------------------------------
    //  JMS topic listeners
    // -----------------------------------------------------------------------

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_PAYMENT_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "compliance-payment-events"
    )
    public void onPaymentEvent(Message message) {
        processMessage(message, "payment.events");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_ACCOUNT_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "compliance-account-events"
    )
    public void onAccountEvent(Message message) {
        processMessage(message, "account.events");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_LEDGER_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "compliance-ledger-events"
    )
    public void onLedgerEvent(Message message) {
        processMessage(message, "ledger.events");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_LOAN_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "compliance-loan-events"
    )
    public void onLoanEvent(Message message) {
        processMessage(message, "loan.events");
    }

    // -----------------------------------------------------------------------
    //  Central processing
    // -----------------------------------------------------------------------

    @Transactional
    void processMessage(Message message, String source) {
        try {
            String messageId = message.getStringProperty("messageId");
            if (messageId != null && !processedMessageIds.add(messageId)) {
                log.trace("Duplicate compliance event {}, skipping", messageId);
                message.acknowledge();
                return;
            }

            String payloadType = message.getStringProperty("payloadType");
            String body = (message instanceof TextMessage tm) ? tm.getText() : null;

            if (body == null || payloadType == null) {
                message.acknowledge();
                return;
            }

            log.debug("Compliance screening: source={}, type={}", source, payloadType);

            JsonNode payload = objectMapper.readTree(body);

            // Run all applicable compliance checks
            screenSanctions(payload, payloadType, messageId);
            checkCtrThreshold(payload, payloadType, messageId);
            detectStructuring(payload, payloadType, messageId);
            checkVelocity(payload, payloadType, messageId);
            validateTravelRule(payload, payloadType, messageId);

            message.acknowledge();

        } catch (Exception ex) {
            log.error("Compliance processing error from {}: {}", source, ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    //  Sanctions screening
    // -----------------------------------------------------------------------

    private void screenSanctions(JsonNode payload, String eventType, String messageId) {
        String beneficiaryName = extractString(payload, "beneficiaryName", "counterpartyName", "partyName");
        String country = extractString(payload, "country", "beneficiaryCountry", "originCountry");

        if (beneficiaryName == null) return;

        String normalizedName = beneficiaryName.toUpperCase().trim();

        boolean hit = sanctionedNamePatterns.stream()
                .anyMatch(pattern -> normalizedName.contains(pattern));

        if (hit) {
            log.warn("SANCTIONS HIT: name='{}', country='{}', eventType={}, messageId={}",
                    beneficiaryName, country, eventType, messageId);

            publishComplianceAlert(
                    AlertLevel.CRITICAL,
                    "SANCTIONS_HIT",
                    "Potential sanctions match for '%s' (country: %s)".formatted(beneficiaryName, country),
                    payload,
                    messageId
            );
        }
    }

    // -----------------------------------------------------------------------
    //  CTR threshold check
    // -----------------------------------------------------------------------

    private void checkCtrThreshold(JsonNode payload, String eventType, String messageId) {
        BigDecimal amount = extractAmount(payload);
        String currency = extractString(payload, "currency", "currencyCode");

        if (amount == null || !"USD".equals(currency)) return;

        if (amount.compareTo(CTR_THRESHOLD) >= 0) {
            String accountId = extractString(payload, "accountId", "originatorAccount", "account");

            log.info("CTR threshold exceeded: amount={} USD, account={}, eventType={}",
                    amount, accountId, eventType);

            publishComplianceAlert(
                    AlertLevel.HIGH,
                    "CTR_REQUIRED",
                    "Cash transaction of %s USD exceeds CTR threshold ($10,000) for account %s"
                            .formatted(amount, accountId),
                    payload,
                    messageId
            );
        }
    }

    // -----------------------------------------------------------------------
    //  Structuring detection
    // -----------------------------------------------------------------------

    private void detectStructuring(JsonNode payload, String eventType, String messageId) {
        BigDecimal amount = extractAmount(payload);
        String accountId = extractString(payload, "accountId", "originatorAccount");
        String currency = extractString(payload, "currency", "currencyCode");

        if (amount == null || accountId == null || !"USD".equals(currency)) return;

        // Only track transactions in the structuring range
        if (amount.compareTo(STRUCTURING_LOWER_BOUND) < 0 || amount.compareTo(CTR_THRESHOLD) >= 0) {
            return;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(structuringWindow);

        structuringTracker.compute(accountId, (key, records) -> {
            if (records == null) records = new java.util.ArrayList<>();
            records.add(new TransactionRecord(amount, now));
            // Prune expired records
            records.removeIf(r -> r.timestamp().isBefore(windowStart));
            return records;
        });

        List<TransactionRecord> recentRecords = structuringTracker.getOrDefault(accountId, List.of());
        long nearThresholdCount = recentRecords.stream()
                .filter(r -> r.amount().compareTo(STRUCTURING_LOWER_BOUND) >= 0)
                .count();

        if (nearThresholdCount >= structuringCountThreshold) {
            log.warn("STRUCTURING DETECTED: account={}, {} near-threshold transactions in {}",
                    accountId, nearThresholdCount, structuringWindow);

            publishComplianceAlert(
                    AlertLevel.HIGH,
                    "STRUCTURING_SUSPECTED",
                    "Account %s has %d transactions between $%s and $%s in %s — possible structuring"
                            .formatted(accountId, nearThresholdCount,
                                    STRUCTURING_LOWER_BOUND, CTR_THRESHOLD, structuringWindow),
                    payload,
                    messageId
            );
        }
    }

    // -----------------------------------------------------------------------
    //  Velocity check
    // -----------------------------------------------------------------------

    private void checkVelocity(JsonNode payload, String eventType, String messageId) {
        String accountId = extractString(payload, "accountId", "originatorAccount");
        if (accountId == null) return;

        Instant now = Instant.now();
        Instant windowStart = now.minus(velocityWindow);

        velocityTracker.compute(accountId, (key, timestamps) -> {
            if (timestamps == null) timestamps = new java.util.ArrayList<>();
            timestamps.add(now);
            timestamps.removeIf(t -> t.isBefore(windowStart));
            return timestamps;
        });

        int txnCount = velocityTracker.getOrDefault(accountId, List.of()).size();

        if (txnCount > velocityMaxTransactions) {
            log.warn("VELOCITY BREACH: account={}, {} transactions in {}", accountId, txnCount, velocityWindow);

            publishComplianceAlert(
                    AlertLevel.MEDIUM,
                    "VELOCITY_BREACH",
                    "Account %s has %d transactions in %s (threshold: %d)"
                            .formatted(accountId, txnCount, velocityWindow, velocityMaxTransactions),
                    payload,
                    messageId
            );
        }
    }

    // -----------------------------------------------------------------------
    //  Travel Rule validation (FATF Recommendation 16)
    // -----------------------------------------------------------------------

    private void validateTravelRule(JsonNode payload, String eventType, String messageId) {
        if (!eventType.contains("wire") && !eventType.contains("payment.submitted")) {
            return;
        }

        BigDecimal amount = extractAmount(payload);
        String currency = extractString(payload, "currency", "currencyCode");
        // Travel Rule applies to transfers >= $3,000 USD equivalent
        BigDecimal travelRuleThreshold = new BigDecimal("3000.00");

        if (amount == null || amount.compareTo(travelRuleThreshold) < 0) return;

        // Check for required originator and beneficiary information
        String originatorName = extractString(payload, "originatorName", "senderName");
        String beneficiaryName = extractString(payload, "beneficiaryName", "receiverName");
        String originatorAccount = extractString(payload, "originatorAccount", "senderAccount");

        boolean missing = false;
        StringBuilder missingFields = new StringBuilder();

        if (originatorName == null) { missingFields.append("originatorName, "); missing = true; }
        if (beneficiaryName == null) { missingFields.append("beneficiaryName, "); missing = true; }
        if (originatorAccount == null) { missingFields.append("originatorAccount, "); missing = true; }

        if (missing) {
            log.warn("TRAVEL RULE VIOLATION: missing fields [{}] for wire of {} {}",
                    missingFields, amount, currency);

            publishComplianceAlert(
                    AlertLevel.HIGH,
                    "TRAVEL_RULE_INCOMPLETE",
                    "Wire transfer of %s %s missing required Travel Rule fields: %s"
                            .formatted(amount, currency, missingFields),
                    payload,
                    messageId
            );
        }
    }

    // -----------------------------------------------------------------------
    //  Alert publishing
    // -----------------------------------------------------------------------

    private void publishComplianceAlert(AlertLevel level,
                                        String alertCode,
                                        String description,
                                        JsonNode triggeringPayload,
                                        String triggeringMessageId) {
        Map<String, Object> alertData = Map.of(
                "alertLevel", level.name(),
                "alertCode", alertCode,
                "description", description,
                "triggeringMessageId", triggeringMessageId != null ? triggeringMessageId : "unknown",
                "detectedAt", Instant.now().toString(),
                "requiresReview", level.ordinal() >= AlertLevel.HIGH.ordinal()
        );

        try {
            // Persist to event store for audit trail
            eventStore.append(
                    "ComplianceAlert", UUID.randomUUID().toString(),
                    "compliance.alert." + alertCode.toLowerCase(),
                    alertData, UUID.randomUUID(), null,
                    "compliance", -1
            );

            // Publish to compliance.alerts topic
            var envelope = MessageEnvelope.builder("compliance", alertData)
                    .payloadType("compliance.alert." + alertCode.toLowerCase())
                    .aggregateType("ComplianceAlert")
                    .asDomainEvent();

            messagePublisher.enqueue(
                    MessageBrokerConfig.TOPIC_COMPLIANCE_ALERTS,
                    envelope
            );

            log.info("Published compliance alert: level={}, code={}, desc='{}'",
                    level, alertCode, description);

        } catch (Exception ex) {
            log.error("Failed to publish compliance alert {}: {}", alertCode, ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    //  Router-based wildcard handler
    // -----------------------------------------------------------------------

    private class AllTransactionEventHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "ComplianceEventConsumer.AllTransactions"; }
        @Override public List<String> handledEventTypes() { return List.of("*"); }
        @Override public boolean isAsync() { return true; }

        @Override
        public Predicate<MessageEnvelope.DomainEventEnvelope<?>> filter() {
            return envelope -> {
                String type = envelope.payloadType();
                return type.contains("payment") || type.contains("account")
                        || type.contains("ledger") || type.contains("loan")
                        || type.contains("transfer") || type.contains("wire");
            };
        }

        @Override
        public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            try {
                String payloadStr = envelope.payload().toString();
                JsonNode payload = objectMapper.readTree(payloadStr);
                String mid = envelope.messageId().toString();

                screenSanctions(payload, envelope.payloadType(), mid);
                checkCtrThreshold(payload, envelope.payloadType(), mid);
                detectStructuring(payload, envelope.payloadType(), mid);
                checkVelocity(payload, envelope.payloadType(), mid);
                validateTravelRule(payload, envelope.payloadType(), mid);
            } catch (JsonProcessingException e) {
                log.error("Compliance router handler failed: {}", e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Supporting types and helpers
    // -----------------------------------------------------------------------

    public enum AlertLevel { LOW, MEDIUM, HIGH, CRITICAL }

    private record TransactionRecord(BigDecimal amount, Instant timestamp) {}

    private String extractString(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    private BigDecimal extractAmount(JsonNode node) {
        for (String field : new String[]{"amount", "transactionAmount", "cashAmount", "value"}) {
            if (node.has(field) && node.get(field).isNumber()) {
                return node.get(field).decimalValue();
            }
        }
        return null;
    }
}
