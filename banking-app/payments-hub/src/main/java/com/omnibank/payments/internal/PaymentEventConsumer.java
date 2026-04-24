package com.omnibank.payments.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.messaging.DomainEventRouter;
import com.omnibank.shared.messaging.MessageBrokerConfig;
import com.omnibank.shared.messaging.MessageEnvelope;
import com.omnibank.shared.messaging.ReliableMessagePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes domain events from account and ledger topics to drive payment
 * lifecycle state transitions. This consumer bridges the gap between the
 * ledger/accounts world and the payments domain:
 *
 * <ul>
 *   <li><b>account.events</b> — account debited/credited confirmations trigger
 *       payment status updates (e.g., funds reserved -> payment validated)</li>
 *   <li><b>ledger.events</b> — posting confirmations trigger settlement
 *       completion; reversal postings trigger payment cancellation</li>
 * </ul>
 *
 * <p>Implements idempotent processing via a deduplication set keyed on the
 * message ID. In a production deployment this would be backed by a persistent
 * store (Redis or a database table); here we use an in-memory set suitable for
 * single-instance operation.</p>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    private final ReliableMessagePublisher messagePublisher;
    private final DomainEventRouter eventRouter;

    /** In-memory deduplication set. Production: use a persistent idempotency store. */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    /** Maximum size of the dedup set before pruning (memory safety). */
    private static final int MAX_DEDUP_SIZE = 100_000;

    public PaymentEventConsumer(PaymentRepository paymentRepository,
                                ObjectMapper objectMapper,
                                ReliableMessagePublisher messagePublisher,
                                DomainEventRouter eventRouter) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.messagePublisher = Objects.requireNonNull(messagePublisher);
        this.eventRouter = Objects.requireNonNull(eventRouter);
    }

    @PostConstruct
    void registerWithRouter() {
        eventRouter.register(new AccountEventHandler());
        eventRouter.register(new LedgerEventHandler());
        log.info("PaymentEventConsumer registered event handlers with DomainEventRouter");
    }

    // -----------------------------------------------------------------------
    //  JMS listeners — account.events topic
    // -----------------------------------------------------------------------

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_ACCOUNT_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "payments-account-events"
    )
    public void onAccountEvent(Message message) {
        processMessage(message, "account.events");
    }

    // -----------------------------------------------------------------------
    //  JMS listeners — ledger.events topic
    // -----------------------------------------------------------------------

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_LEDGER_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "payments-ledger-events"
    )
    public void onLedgerEvent(Message message) {
        processMessage(message, "ledger.events");
    }

    // -----------------------------------------------------------------------
    //  Central message processor
    // -----------------------------------------------------------------------

    @Transactional
    void processMessage(Message message, String source) {
        try {
            String messageId = message.getStringProperty("messageId");
            if (messageId != null && !tryMarkProcessed(messageId)) {
                log.debug("Duplicate message {} from {}, skipping", messageId, source);
                message.acknowledge();
                return;
            }

            String payloadType = message.getStringProperty("payloadType");
            String body = extractBody(message);

            if (body == null || payloadType == null) {
                log.warn("Received message from {} with missing payload or type, acknowledging and discarding",
                        source);
                message.acknowledge();
                return;
            }

            log.debug("Processing {} event: type={}, messageId={}", source, payloadType, messageId);

            switch (payloadType) {
                case "account.debited"          -> handleAccountDebited(body);
                case "account.credited"         -> handleAccountCredited(body);
                case "account.debit.failed"     -> handleAccountDebitFailed(body);
                case "account.hold.placed"      -> handleAccountHoldPlaced(body);
                case "ledger.entry.posted"      -> handleLedgerEntryPosted(body);
                case "ledger.entry.reversed"    -> handleLedgerEntryReversed(body);
                case "ledger.settlement.completed" -> handleSettlementCompleted(body);
                case "ledger.settlement.failed" -> handleSettlementFailed(body);
                default -> log.trace("Ignoring unhandled event type '{}' from {}", payloadType, source);
            }

            message.acknowledge();

        } catch (Exception ex) {
            log.error("Error processing message from {}: {}", source, ex.getMessage(), ex);
            // Do not acknowledge — broker will redeliver
        }
    }

    // -----------------------------------------------------------------------
    //  Account event handlers
    // -----------------------------------------------------------------------

    private void handleAccountDebited(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            if (payment.status() == PaymentStatus.VALIDATED) {
                payment.submit(Instant.now());
                log.info("Payment {} advanced to SUBMITTED after account debit confirmation", paymentRef);
                publishPaymentStatusChange(payment, "payment.status.updated");
            }
        });
    }

    private void handleAccountCredited(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        if (paymentRef == null) return;

        log.info("Account credit confirmed for payment reference {}", paymentRef);
        // Credit side confirmation — update beneficiary-side tracking if applicable
    }

    private void handleAccountDebitFailed(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        String reason = node.has("reason") ? node.get("reason").asText() : "Insufficient funds";
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            if (payment.status() != PaymentStatus.SETTLED && payment.status() != PaymentStatus.CANCELED) {
                payment.cancel("Account debit failed: " + reason);
                log.warn("Payment {} cancelled due to debit failure: {}", paymentRef, reason);
                publishPaymentStatusChange(payment, "payment.cancelled");
            }
        });
    }

    private void handleAccountHoldPlaced(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        BigDecimal heldAmount = node.has("amount") ?
                node.get("amount").decimalValue() : BigDecimal.ZERO;
        if (paymentRef == null) return;

        log.info("Funds hold of {} placed for payment {}", heldAmount, paymentRef);
    }

    // -----------------------------------------------------------------------
    //  Ledger event handlers
    // -----------------------------------------------------------------------

    private void handleLedgerEntryPosted(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            log.info("Ledger posting confirmed for payment {}, current status: {}",
                    paymentRef, payment.status());
        });
    }

    private void handleLedgerEntryReversed(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        String reversalReason = node.has("reversalReason") ?
                node.get("reversalReason").asText() : "Ledger reversal";
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            if (payment.status() != PaymentStatus.SETTLED && payment.status() != PaymentStatus.CANCELED) {
                payment.cancel("Ledger reversal: " + reversalReason);
                log.warn("Payment {} cancelled due to ledger reversal: {}", paymentRef, reversalReason);
                publishPaymentStatusChange(payment, "payment.reversed");
            }
        });
    }

    private void handleSettlementCompleted(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        String settlementRef = node.has("settlementRef") ?
                node.get("settlementRef").asText() : null;
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            log.info("Settlement completed for payment {}, settlementRef={}", paymentRef, settlementRef);
            publishPaymentStatusChange(payment, "payment.settled");
        });
    }

    private void handleSettlementFailed(String body) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(body);
        String paymentRef = extractPaymentRef(node);
        String failureCode = node.has("failureCode") ?
                node.get("failureCode").asText() : "UNKNOWN";
        if (paymentRef == null) return;

        paymentRepository.findById(UUID.fromString(paymentRef)).ifPresent(payment -> {
            if (payment.status() != PaymentStatus.SETTLED && payment.status() != PaymentStatus.CANCELED) {
                payment.cancel("Settlement failed: " + failureCode);
                log.error("Payment {} settlement failed with code {}", paymentRef, failureCode);
                publishPaymentStatusChange(payment, "payment.settlement.failed");
            }
        });
    }

    // -----------------------------------------------------------------------
    //  Internal router-based handlers
    // -----------------------------------------------------------------------

    private class AccountEventHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "PaymentEventConsumer.AccountEventHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("account.debited", "account.credited",
                    "account.debit.failed", "account.hold.placed");
        }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            String payloadJson = envelope.payload().toString();
            try {
                switch (envelope.payloadType()) {
                    case "account.debited"      -> handleAccountDebited(payloadJson);
                    case "account.credited"     -> handleAccountCredited(payloadJson);
                    case "account.debit.failed" -> handleAccountDebitFailed(payloadJson);
                    case "account.hold.placed"  -> handleAccountHoldPlaced(payloadJson);
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to process account event", e);
            }
        }
    }

    private class LedgerEventHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "PaymentEventConsumer.LedgerEventHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("ledger.entry.posted", "ledger.entry.reversed",
                    "ledger.settlement.completed", "ledger.settlement.failed");
        }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            String payloadJson = envelope.payload().toString();
            try {
                switch (envelope.payloadType()) {
                    case "ledger.entry.posted"         -> handleLedgerEntryPosted(payloadJson);
                    case "ledger.entry.reversed"       -> handleLedgerEntryReversed(payloadJson);
                    case "ledger.settlement.completed"  -> handleSettlementCompleted(payloadJson);
                    case "ledger.settlement.failed"     -> handleSettlementFailed(payloadJson);
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to process ledger event", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private String extractPaymentRef(JsonNode node) {
        if (node.has("paymentRef")) return node.get("paymentRef").asText();
        if (node.has("paymentId")) return node.get("paymentId").asText();
        if (node.has("referenceId")) return node.get("referenceId").asText();
        log.trace("No payment reference found in event payload");
        return null;
    }

    private String extractBody(Message message) throws Exception {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        return null;
    }

    private boolean tryMarkProcessed(String messageId) {
        if (processedMessageIds.size() > MAX_DEDUP_SIZE) {
            processedMessageIds.clear();
            log.info("Dedup set pruned (exceeded {} entries)", MAX_DEDUP_SIZE);
        }
        return processedMessageIds.add(messageId);
    }

    @Transactional
    void publishPaymentStatusChange(PaymentEntity payment, String eventType) {
        try {
            var envelope = MessageEnvelope.builder("payments-hub",
                            Map.of("paymentId", payment.id().toString(),
                                    "status", payment.status().name(),
                                    "rail", payment.rail().name()))
                    .payloadType(eventType)
                    .aggregateType("Payment")
                    .aggregateId(payment.id().toString())
                    .asDomainEvent();

            messagePublisher.enqueue(
                    MessageBrokerConfig.TOPIC_PAYMENT_EVENTS,
                    envelope,
                    payment.id().toString()
            );
        } catch (Exception ex) {
            log.error("Failed to publish payment status change for {}: {}",
                    payment.id(), ex.getMessage());
        }
    }
}
