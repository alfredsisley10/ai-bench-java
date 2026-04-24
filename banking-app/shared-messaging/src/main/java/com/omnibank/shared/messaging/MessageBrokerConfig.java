package com.omnibank.shared.messaging;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Central JMS broker configuration for the OmniBank messaging infrastructure.
 *
 * <p>Defines all topic and queue destinations used across modules, configures
 * dead-letter queues, retry policies, message time-to-live, and listener
 * container factories for both synchronous and asynchronous consumption.</p>
 *
 * <p>In production the embedded broker URL is replaced by an external Artemis
 * or MSK cluster via the {@code omnibank.messaging.broker-url} property.</p>
 */
@Configuration
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@EnableJms
@EnableScheduling
public class MessageBrokerConfig {

    private static final Logger log = LoggerFactory.getLogger(MessageBrokerConfig.class);

    // -----------------------------------------------------------------------
    //  Destination constants — all modules reference these.
    // -----------------------------------------------------------------------

    public static final String TOPIC_PAYMENT_EVENTS    = "payment.events";
    public static final String TOPIC_LEDGER_EVENTS     = "ledger.events";
    public static final String TOPIC_ACCOUNT_EVENTS    = "account.events";
    public static final String TOPIC_LOAN_EVENTS       = "loan.events";
    public static final String TOPIC_COMPLIANCE_ALERTS = "compliance.alerts";
    public static final String TOPIC_FRAUD_ALERTS      = "fraud.alerts";

    public static final String QUEUE_PAYMENT_COMMANDS  = "payment.commands";
    public static final String QUEUE_LEDGER_COMMANDS   = "ledger.commands";
    public static final String QUEUE_COMPLIANCE_SCREEN = "compliance.screening";
    public static final String QUEUE_NOTIFICATION_SEND = "notification.send";
    public static final String QUEUE_SAGA_COMMANDS     = "saga.commands";

    public static final String DLQ_PREFIX = "DLQ.";

    /** All managed topic destinations, for programmatic iteration. */
    public static final List<String> ALL_TOPICS = List.of(
            TOPIC_PAYMENT_EVENTS, TOPIC_LEDGER_EVENTS, TOPIC_ACCOUNT_EVENTS,
            TOPIC_LOAN_EVENTS, TOPIC_COMPLIANCE_ALERTS, TOPIC_FRAUD_ALERTS
    );

    /** All managed queue destinations. */
    public static final List<String> ALL_QUEUES = List.of(
            QUEUE_PAYMENT_COMMANDS, QUEUE_LEDGER_COMMANDS,
            QUEUE_COMPLIANCE_SCREEN, QUEUE_NOTIFICATION_SEND, QUEUE_SAGA_COMMANDS
    );

    // -----------------------------------------------------------------------
    //  Retry / TTL policy
    // -----------------------------------------------------------------------

    /** Maximum delivery attempts before routing to the dead-letter queue. */
    @Value("${omnibank.messaging.max-redeliveries:5}")
    private int maxRedeliveries;

    /** Initial delay between retries (ms). Exponential back-off factor is 2. */
    @Value("${omnibank.messaging.redelivery-delay-ms:1000}")
    private long redeliveryDelayMs;

    /** Default message time-to-live. Compliance alerts use a shorter override. */
    @Value("${omnibank.messaging.default-ttl:PT24H}")
    private Duration defaultTtl;

    /** Compliance-specific TTL — these alerts expire quickly if not consumed. */
    @Value("${omnibank.messaging.compliance-ttl:PT1H}")
    private Duration complianceTtl;

    @Value("${omnibank.messaging.broker-url:vm://embedded?broker.persistent=false}")
    private String brokerUrl;

    @Value("${omnibank.messaging.session-cache-size:10}")
    private int sessionCacheSize;

    @Value("${omnibank.messaging.concurrency:3-10}")
    private String concurrency;

    // -----------------------------------------------------------------------
    //  Connection factory
    // -----------------------------------------------------------------------

    @Bean
    public ConnectionFactory jmsConnectionFactory() {
        log.info("Configuring JMS connection factory: url={}, cacheSize={}", brokerUrl, sessionCacheSize);

        CachingConnectionFactory cachingFactory = new CachingConnectionFactory();
        cachingFactory.setSessionCacheSize(sessionCacheSize);
        cachingFactory.setReconnectOnException(true);
        return cachingFactory;
    }

    // -----------------------------------------------------------------------
    //  Message converter — JSON via Jackson
    // -----------------------------------------------------------------------

    @Bean
    public MessageConverter jacksonJmsMessageConverter() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_omnibankType");
        converter.setTypeIdMappings(buildTypeIdMappings());
        return converter;
    }

    private Map<String, Class<?>> buildTypeIdMappings() {
        return Map.of(
                "DomainEventEnvelope", MessageEnvelope.DomainEventEnvelope.class,
                "CommandEnvelope",     MessageEnvelope.CommandEnvelope.class,
                "QueryEnvelope",       MessageEnvelope.QueryEnvelope.class
        );
    }

    // -----------------------------------------------------------------------
    //  JmsTemplate — default publisher template
    // -----------------------------------------------------------------------

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory,
                                   MessageConverter jacksonJmsMessageConverter) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setMessageConverter(jacksonJmsMessageConverter);
        template.setTimeToLive(defaultTtl.toMillis());
        template.setDeliveryPersistent(true);
        template.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        template.setExplicitQosEnabled(true);
        return template;
    }

    /**
     * Secondary template for compliance-related messages with a shorter TTL.
     * Compliance alerts that are not consumed within the window are considered
     * stale and should be reprocessed from the event store rather than replayed
     * from the broker.
     */
    @Bean
    public JmsTemplate complianceJmsTemplate(ConnectionFactory connectionFactory,
                                             MessageConverter jacksonJmsMessageConverter) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setMessageConverter(jacksonJmsMessageConverter);
        template.setTimeToLive(complianceTtl.toMillis());
        template.setDeliveryPersistent(true);
        template.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        template.setExplicitQosEnabled(true);
        template.setDefaultDestinationName(TOPIC_COMPLIANCE_ALERTS);
        return template;
    }

    // -----------------------------------------------------------------------
    //  Listener container factories
    // -----------------------------------------------------------------------

    /**
     * Default topic listener factory — pub/sub semantics with durable
     * subscriptions so consumers can disconnect and reconnect without losing
     * events published in the interim.
     */
    @Bean
    public JmsListenerContainerFactory<?> topicListenerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonJmsMessageConverter) {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonJmsMessageConverter);
        factory.setPubSubDomain(true);
        factory.setSubscriptionDurable(true);
        factory.setConcurrency(concurrency);
        factory.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        factory.setErrorHandler(t ->
                log.error("Topic listener error — message will be redelivered", t));
        return factory;
    }

    /**
     * Queue listener factory — point-to-point semantics with competing consumers.
     * Used for commands where exactly-once processing is required.
     */
    @Bean
    public JmsListenerContainerFactory<?> queueListenerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonJmsMessageConverter) {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonJmsMessageConverter);
        factory.setPubSubDomain(false);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(true);
        factory.setErrorHandler(t ->
                log.error("Queue listener error — message will be redelivered", t));
        return factory;
    }

    /**
     * Dead-letter listener factory — consumes from DLQ destinations for
     * alerting, metrics, and manual reprocessing workflows.
     */
    @Bean
    public JmsListenerContainerFactory<?> dlqListenerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonJmsMessageConverter) {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonJmsMessageConverter);
        factory.setPubSubDomain(false);
        factory.setConcurrency("1");
        factory.setSessionTransacted(true);
        return factory;
    }

    // -----------------------------------------------------------------------
    //  Destination metadata — exposed for ops tooling
    // -----------------------------------------------------------------------

    public record DestinationMetadata(
            String name,
            DestinationType type,
            String deadLetterQueue,
            Duration messageTtl,
            int maxRedeliveries
    ) {}

    public enum DestinationType { TOPIC, QUEUE }

    @Bean
    public List<DestinationMetadata> managedDestinations() {
        var destinations = new java.util.ArrayList<DestinationMetadata>();

        for (String topic : ALL_TOPICS) {
            Duration ttl = topic.equals(TOPIC_COMPLIANCE_ALERTS) ? complianceTtl : defaultTtl;
            destinations.add(new DestinationMetadata(
                    topic, DestinationType.TOPIC, DLQ_PREFIX + topic, ttl, maxRedeliveries));
        }
        for (String queue : ALL_QUEUES) {
            destinations.add(new DestinationMetadata(
                    queue, DestinationType.QUEUE, DLQ_PREFIX + queue, defaultTtl, maxRedeliveries));
        }

        log.info("Registered {} managed messaging destinations ({} topics, {} queues)",
                destinations.size(), ALL_TOPICS.size(), ALL_QUEUES.size());
        return List.copyOf(destinations);
    }
}
