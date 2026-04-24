package com.omnibank.notifications.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.notifications.api.NotificationService;
import com.omnibank.notifications.api.NotificationService.Channel;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.messaging.DomainEventRouter;
import com.omnibank.shared.messaging.MessageBrokerConfig;
import com.omnibank.shared.messaging.MessageEnvelope;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for domain events across the OmniBank platform and generates
 * customer-facing notifications through the appropriate channels (email, SMS,
 * push, secure inbox).
 *
 * <p>Notification routing logic:</p>
 * <ul>
 *   <li><b>Payment events</b> — email + push for status changes; SMS for
 *       failures and high-value settlements</li>
 *   <li><b>Account events</b> — push for balance changes; email for account
 *       lifecycle events (opened, closed, frozen)</li>
 *   <li><b>Compliance alerts</b> — secure inbox only (no external channel for
 *       compliance-sensitive information)</li>
 *   <li><b>Fraud alerts</b> — SMS + push for immediate customer awareness;
 *       email for detailed follow-up</li>
 *   <li><b>Loan events</b> — email for disbursement, payment due, and
 *       delinquency notices</li>
 * </ul>
 *
 * <p>Each notification is assembled from a template with dynamic data bindings.
 * Templates are identified by a template key (e.g., {@code "payment.submitted"})
 * and the consumer populates the data map from the event payload.</p>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' h:mm a z")
                    .withZone(ZoneId.of("America/New_York"));

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final DomainEventRouter eventRouter;

    /** High-value payment threshold for SMS notifications. */
    @Value("${omnibank.notifications.high-value-threshold:50000}")
    private BigDecimal highValueThreshold;

    /** Whether to send SMS for non-critical events (can be toggled per env). */
    @Value("${omnibank.notifications.sms-enabled:true}")
    private boolean smsEnabled;

    /** Rate limiter: max notifications per customer per hour. */
    @Value("${omnibank.notifications.rate-limit-per-hour:20}")
    private int rateLimitPerHour;

    /** Deduplication. */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    /** Rate limiting tracker: customerId -> timestamps of recent notifications. */
    private final ConcurrentHashMap<String, List<Instant>> rateLimiter = new ConcurrentHashMap<>();

    public NotificationEventConsumer(NotificationService notificationService,
                                     ObjectMapper objectMapper,
                                     DomainEventRouter eventRouter) {
        this.notificationService = Objects.requireNonNull(notificationService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.eventRouter = Objects.requireNonNull(eventRouter);
    }

    @PostConstruct
    void registerWithRouter() {
        eventRouter.register(new PaymentNotificationHandler());
        eventRouter.register(new AccountNotificationHandler());
        eventRouter.register(new ComplianceNotificationHandler());
        eventRouter.register(new FraudNotificationHandler());
        eventRouter.register(new LoanNotificationHandler());
        log.info("NotificationEventConsumer registered 5 notification handlers with DomainEventRouter");
    }

    // -----------------------------------------------------------------------
    //  JMS topic listeners
    // -----------------------------------------------------------------------

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_PAYMENT_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "notifications-payment-events"
    )
    public void onPaymentEvent(Message message) {
        processMessage(message, "payment.events");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_ACCOUNT_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "notifications-account-events"
    )
    public void onAccountEvent(Message message) {
        processMessage(message, "account.events");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_COMPLIANCE_ALERTS,
            containerFactory = "topicListenerFactory",
            subscription = "notifications-compliance-alerts"
    )
    public void onComplianceAlert(Message message) {
        processMessage(message, "compliance.alerts");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_FRAUD_ALERTS,
            containerFactory = "topicListenerFactory",
            subscription = "notifications-fraud-alerts"
    )
    public void onFraudAlert(Message message) {
        processMessage(message, "fraud.alerts");
    }

    @JmsListener(
            destination = MessageBrokerConfig.TOPIC_LOAN_EVENTS,
            containerFactory = "topicListenerFactory",
            subscription = "notifications-loan-events"
    )
    public void onLoanEvent(Message message) {
        processMessage(message, "loan.events");
    }

    // -----------------------------------------------------------------------
    //  Central processor
    // -----------------------------------------------------------------------

    @Transactional
    void processMessage(Message message, String source) {
        try {
            String messageId = message.getStringProperty("messageId");
            if (messageId != null && !processedMessageIds.add(messageId)) {
                message.acknowledge();
                return;
            }

            String payloadType = message.getStringProperty("payloadType");
            String body = (message instanceof TextMessage tm) ? tm.getText() : null;

            if (body == null || payloadType == null) {
                message.acknowledge();
                return;
            }

            JsonNode payload = objectMapper.readTree(body);
            String customerId = extractCustomerId(payload);

            if (customerId == null) {
                log.trace("No customer ID in event {}, skipping notification", payloadType);
                message.acknowledge();
                return;
            }

            if (!checkRateLimit(customerId)) {
                log.info("Rate limit reached for customer {}, suppressing notification for {}",
                        customerId, payloadType);
                message.acknowledge();
                return;
            }

            dispatchNotification(payloadType, payload, customerId, source);
            message.acknowledge();

        } catch (Exception ex) {
            log.error("Notification processing error from {}: {}", source, ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    //  Notification dispatch by event type
    // -----------------------------------------------------------------------

    private void dispatchNotification(String eventType, JsonNode payload,
                                      String customerId, String source) {
        switch (eventType) {
            // Payment events
            case "payment.submitted" -> sendPaymentSubmitted(customerId, payload);
            case "payment.status.updated" -> sendPaymentStatusUpdate(customerId, payload);
            case "payment.settled" -> sendPaymentSettled(customerId, payload);
            case "payment.cancelled", "payment.reversed" -> sendPaymentCancelled(customerId, payload);
            case "payment.settlement.failed" -> sendPaymentSettlementFailed(customerId, payload);

            // Account events
            case "account.debited" -> sendAccountDebited(customerId, payload);
            case "account.credited" -> sendAccountCredited(customerId, payload);
            case "account.opened" -> sendAccountOpened(customerId, payload);
            case "account.closed" -> sendAccountClosed(customerId, payload);
            case "account.frozen" -> sendAccountFrozen(customerId, payload);

            // Compliance alerts (secure inbox only)
            case String s when s.startsWith("compliance.alert") ->
                    sendComplianceNotice(customerId, payload, s);

            // Fraud alerts (urgent multi-channel)
            case String s when s.startsWith("fraud.alert") ->
                    sendFraudAlert(customerId, payload, s);

            // Loan events
            case "loan.disbursed" -> sendLoanDisbursed(customerId, payload);
            case "loan.payment.due" -> sendLoanPaymentDue(customerId, payload);
            case "loan.payment.received" -> sendLoanPaymentReceived(customerId, payload);
            case "loan.delinquent" -> sendLoanDelinquent(customerId, payload);

            default -> log.trace("No notification template for event type '{}'", eventType);
        }
    }

    // -----------------------------------------------------------------------
    //  Payment notifications
    // -----------------------------------------------------------------------

    private void sendPaymentSubmitted(String customerId, JsonNode payload) {
        Map<String, Object> data = buildPaymentData(payload);
        data.put("subject", "Payment Submitted");
        data.put("headline", "Your payment has been submitted for processing");

        sendToCustomer(customerId, "payment.submitted", data,
                Channel.EMAIL, Channel.PUSH);
    }

    private void sendPaymentStatusUpdate(String customerId, JsonNode payload) {
        Map<String, Object> data = buildPaymentData(payload);
        String status = extractText(payload, "status");
        data.put("subject", "Payment Update: " + status);
        data.put("headline", "Your payment status has changed to " + status);

        sendToCustomer(customerId, "payment.status_update", data, Channel.PUSH);
    }

    private void sendPaymentSettled(String customerId, JsonNode payload) {
        Map<String, Object> data = buildPaymentData(payload);
        data.put("subject", "Payment Settled");
        data.put("headline", "Your payment has been settled successfully");

        BigDecimal amount = extractAmount(payload);
        boolean highValue = amount != null && amount.compareTo(highValueThreshold) >= 0;

        if (highValue && smsEnabled) {
            sendToCustomer(customerId, "payment.settled", data,
                    Channel.EMAIL, Channel.PUSH, Channel.SMS);
        } else {
            sendToCustomer(customerId, "payment.settled", data,
                    Channel.EMAIL, Channel.PUSH);
        }
    }

    private void sendPaymentCancelled(String customerId, JsonNode payload) {
        Map<String, Object> data = buildPaymentData(payload);
        String reason = extractText(payload, "reason");
        data.put("subject", "Payment Cancelled");
        data.put("headline", "Your payment has been cancelled");
        data.put("reason", reason != null ? reason : "Contact customer service for details");

        sendToCustomer(customerId, "payment.cancelled", data,
                Channel.EMAIL, Channel.PUSH, Channel.SMS);
    }

    private void sendPaymentSettlementFailed(String customerId, JsonNode payload) {
        Map<String, Object> data = buildPaymentData(payload);
        data.put("subject", "Payment Settlement Failed");
        data.put("headline", "There was an issue settling your payment");

        sendToCustomer(customerId, "payment.settlement_failed", data,
                Channel.EMAIL, Channel.PUSH, Channel.SMS);
    }

    // -----------------------------------------------------------------------
    //  Account notifications
    // -----------------------------------------------------------------------

    private void sendAccountDebited(String customerId, JsonNode payload) {
        Map<String, Object> data = buildAccountData(payload);
        data.put("subject", "Account Debit Notification");
        data.put("headline", "A debit has been posted to your account");

        sendToCustomer(customerId, "account.debited", data, Channel.PUSH);
    }

    private void sendAccountCredited(String customerId, JsonNode payload) {
        Map<String, Object> data = buildAccountData(payload);
        data.put("subject", "Deposit Received");
        data.put("headline", "A credit has been posted to your account");

        sendToCustomer(customerId, "account.credited", data, Channel.PUSH);
    }

    private void sendAccountOpened(String customerId, JsonNode payload) {
        Map<String, Object> data = buildAccountData(payload);
        data.put("subject", "Welcome to OmniBank");
        data.put("headline", "Your new account is ready");

        sendToCustomer(customerId, "account.opened", data,
                Channel.EMAIL, Channel.PUSH, Channel.SECURE_INBOX);
    }

    private void sendAccountClosed(String customerId, JsonNode payload) {
        Map<String, Object> data = buildAccountData(payload);
        data.put("subject", "Account Closed");
        data.put("headline", "Your account has been closed");

        sendToCustomer(customerId, "account.closed", data,
                Channel.EMAIL, Channel.SECURE_INBOX);
    }

    private void sendAccountFrozen(String customerId, JsonNode payload) {
        Map<String, Object> data = buildAccountData(payload);
        data.put("subject", "Account Restricted");
        data.put("headline", "A restriction has been placed on your account");

        sendToCustomer(customerId, "account.frozen", data,
                Channel.EMAIL, Channel.SMS, Channel.SECURE_INBOX);
    }

    // -----------------------------------------------------------------------
    //  Compliance notifications (secure inbox only)
    // -----------------------------------------------------------------------

    private void sendComplianceNotice(String customerId, JsonNode payload, String eventType) {
        Map<String, Object> data = new HashMap<>();
        data.put("subject", "Compliance Notice");
        data.put("headline", "A compliance review item requires your attention");
        data.put("alertCode", extractText(payload, "alertCode"));
        data.put("timestamp", DISPLAY_DATE.format(Instant.now()));

        // Compliance-sensitive: only secure inbox, never external channels
        sendToCustomer(customerId, "compliance.notice", data, Channel.SECURE_INBOX);
    }

    // -----------------------------------------------------------------------
    //  Fraud alert notifications (urgent multi-channel)
    // -----------------------------------------------------------------------

    private void sendFraudAlert(String customerId, JsonNode payload, String eventType) {
        Map<String, Object> data = new HashMap<>();
        data.put("subject", "Security Alert - Unusual Activity Detected");
        data.put("headline", "We detected unusual activity on your account");
        data.put("alertType", extractText(payload, "fraudType"));
        data.put("detectedAt", DISPLAY_DATE.format(Instant.now()));
        data.put("actionRequired", "Please review your recent transactions and contact us if unauthorized");

        String transactionDesc = extractText(payload, "transactionDescription");
        if (transactionDesc != null) {
            data.put("transactionDescription", transactionDesc);
        }

        // Fraud alerts go to all channels for maximum visibility
        sendToCustomer(customerId, "fraud.alert", data,
                Channel.SMS, Channel.PUSH, Channel.EMAIL, Channel.SECURE_INBOX);
    }

    // -----------------------------------------------------------------------
    //  Loan notifications
    // -----------------------------------------------------------------------

    private void sendLoanDisbursed(String customerId, JsonNode payload) {
        Map<String, Object> data = buildLoanData(payload);
        data.put("subject", "Loan Disbursement Confirmation");
        data.put("headline", "Your loan has been disbursed");

        sendToCustomer(customerId, "loan.disbursed", data,
                Channel.EMAIL, Channel.PUSH, Channel.SECURE_INBOX);
    }

    private void sendLoanPaymentDue(String customerId, JsonNode payload) {
        Map<String, Object> data = buildLoanData(payload);
        String dueDate = extractText(payload, "dueDate");
        data.put("subject", "Loan Payment Reminder");
        data.put("headline", "Your loan payment is due on " + dueDate);

        sendToCustomer(customerId, "loan.payment_due", data,
                Channel.EMAIL, Channel.PUSH);
    }

    private void sendLoanPaymentReceived(String customerId, JsonNode payload) {
        Map<String, Object> data = buildLoanData(payload);
        data.put("subject", "Loan Payment Received");
        data.put("headline", "We received your loan payment");

        sendToCustomer(customerId, "loan.payment_received", data, Channel.PUSH);
    }

    private void sendLoanDelinquent(String customerId, JsonNode payload) {
        Map<String, Object> data = buildLoanData(payload);
        data.put("subject", "Past Due Notice");
        data.put("headline", "Your loan payment is past due");
        data.put("actionRequired", "Please make a payment immediately to avoid additional fees");

        sendToCustomer(customerId, "loan.delinquent", data,
                Channel.EMAIL, Channel.SMS, Channel.PUSH, Channel.SECURE_INBOX);
    }

    // -----------------------------------------------------------------------
    //  Router-based handlers
    // -----------------------------------------------------------------------

    private class PaymentNotificationHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "NotificationEventConsumer.PaymentHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("payment.submitted", "payment.status.updated",
                    "payment.settled", "payment.cancelled", "payment.reversed",
                    "payment.settlement.failed");
        }
        @Override public boolean isAsync() { return true; }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            routerDispatch(envelope);
        }
    }

    private class AccountNotificationHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "NotificationEventConsumer.AccountHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("account.debited", "account.credited",
                    "account.opened", "account.closed", "account.frozen");
        }
        @Override public boolean isAsync() { return true; }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            routerDispatch(envelope);
        }
    }

    private class ComplianceNotificationHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "NotificationEventConsumer.ComplianceHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("compliance.alert.sanctions_hit", "compliance.alert.ctr_required",
                    "compliance.alert.structuring_suspected", "compliance.alert.velocity_breach",
                    "compliance.alert.travel_rule_incomplete");
        }
        @Override public boolean isAsync() { return true; }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            routerDispatch(envelope);
        }
    }

    private class FraudNotificationHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "NotificationEventConsumer.FraudHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("fraud.alert.suspicious_transaction", "fraud.alert.account_takeover",
                    "fraud.alert.card_compromise", "fraud.alert.identity_theft");
        }
        @Override public boolean isAsync() { return true; }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            routerDispatch(envelope);
        }
    }

    private class LoanNotificationHandler implements DomainEventRouter.DomainEventHandler {
        @Override public String name() { return "NotificationEventConsumer.LoanHandler"; }
        @Override public List<String> handledEventTypes() {
            return List.of("loan.disbursed", "loan.payment.due",
                    "loan.payment.received", "loan.delinquent");
        }
        @Override public boolean isAsync() { return true; }
        @Override public void handle(MessageEnvelope.DomainEventEnvelope<?> envelope) {
            routerDispatch(envelope);
        }
    }

    private void routerDispatch(MessageEnvelope.DomainEventEnvelope<?> envelope) {
        try {
            String payloadStr = envelope.payload().toString();
            JsonNode payload = objectMapper.readTree(payloadStr);
            String customerId = extractCustomerId(payload);
            if (customerId != null && checkRateLimit(customerId)) {
                dispatchNotification(envelope.payloadType(), payload, customerId, envelope.sourceModule());
            }
        } catch (JsonProcessingException e) {
            log.error("Notification router handler failed for {}: {}",
                    envelope.payloadType(), e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void sendToCustomer(String customerId, String template,
                                Map<String, Object> data, Channel... channels) {
        CustomerId cid = new CustomerId(java.util.UUID.fromString(customerId));
        data.put("timestamp", DISPLAY_DATE.format(Instant.now()));

        for (Channel channel : channels) {
            try {
                notificationService.send(cid, channel, template, data);
                log.debug("Sent {} notification to customer {} via {}", template, customerId, channel);
            } catch (Exception ex) {
                log.error("Failed to send {} notification to {} via {}: {}",
                        template, customerId, channel, ex.getMessage());
            }
        }
    }

    private Map<String, Object> buildPaymentData(JsonNode payload) {
        Map<String, Object> data = new HashMap<>();
        putIfPresent(data, payload, "paymentId");
        putIfPresent(data, payload, "amount");
        putIfPresent(data, payload, "currency");
        putIfPresent(data, payload, "status");
        putIfPresent(data, payload, "rail");
        putIfPresent(data, payload, "beneficiaryName");
        return data;
    }

    private Map<String, Object> buildAccountData(JsonNode payload) {
        Map<String, Object> data = new HashMap<>();
        putIfPresent(data, payload, "accountId");
        putIfPresent(data, payload, "accountNumber");
        putIfPresent(data, payload, "amount");
        putIfPresent(data, payload, "currency");
        putIfPresent(data, payload, "balance");
        return data;
    }

    private Map<String, Object> buildLoanData(JsonNode payload) {
        Map<String, Object> data = new HashMap<>();
        putIfPresent(data, payload, "loanId");
        putIfPresent(data, payload, "amount");
        putIfPresent(data, payload, "currency");
        putIfPresent(data, payload, "dueDate");
        putIfPresent(data, payload, "outstandingBalance");
        return data;
    }

    private void putIfPresent(Map<String, Object> data, JsonNode payload, String field) {
        if (payload.has(field) && !payload.get(field).isNull()) {
            data.put(field, payload.get(field).asText());
        }
    }

    private String extractCustomerId(JsonNode payload) {
        for (String field : new String[]{"customerId", "customer_id", "accountHolder",
                "originatorCustomerId", "beneficiaryCustomerId"}) {
            if (payload.has(field) && !payload.get(field).isNull()) {
                return payload.get(field).asText();
            }
        }
        return null;
    }

    private String extractText(JsonNode payload, String field) {
        return payload.has(field) && !payload.get(field).isNull() ? payload.get(field).asText() : null;
    }

    private BigDecimal extractAmount(JsonNode payload) {
        if (payload.has("amount") && payload.get("amount").isNumber()) {
            return payload.get("amount").decimalValue();
        }
        return null;
    }

    private boolean checkRateLimit(String customerId) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(3600);

        rateLimiter.compute(customerId, (key, timestamps) -> {
            if (timestamps == null) timestamps = new java.util.ArrayList<>();
            timestamps.removeIf(t -> t.isBefore(windowStart));
            timestamps.add(now);
            return timestamps;
        });

        return rateLimiter.get(customerId).size() <= rateLimitPerHour;
    }
}
