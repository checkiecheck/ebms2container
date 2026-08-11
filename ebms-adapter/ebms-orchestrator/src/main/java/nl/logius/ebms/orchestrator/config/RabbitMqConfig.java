package nl.logius.ebms.orchestrator.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ-configuratie voor de ebms-orchestrator.
 *
 * <p>Definieert de topologie:
 * <pre>
 *   Exchange:  ebms.exchange  (direct, durable)
 *       │
 *       ├─[routing: inbound]──► Queue: ebms.inbound.messages
 *       ├─[routing: outbound]─► Queue: ebms.outbound.messages
 *       └─[routing: audit]────► Queue: ebms.audit.events
 * </pre>
 *
 * <p>Alle queues zijn {@code durable=true} zodat berichten behouden blijven
 * bij herstart van RabbitMQ (Reliable Messaging vereiste).
 */
@Configuration
public class RabbitMqConfig {

    // ── Exchange ─────────────────────────────────────────────────────────────
    public static final String EXCHANGE_EBMS = "ebms.exchange";

    // ── Queues ────────────────────────────────────────────────────────────────
    /** Binnenkomende ebMS2-berichten – wachten op orchestrator-verwerking. */
    public static final String QUEUE_INBOUND   = "ebms.inbound.messages";
    /** Uitgaande ebMS2-berichten / ACK's – wachten op verzending. */
    public static final String QUEUE_OUTBOUND  = "ebms.outbound.messages";
    /** Audit-events – forwarden naar auditor-service (Fase 3). */
    public static final String QUEUE_AUDIT     = "ebms.audit.events";
    /** Dead Letter Queue voor berichten die definitief gefaald zijn. */
    public static final String QUEUE_DLQ       = "ebms.dlq";

    // ── Routing keys ─────────────────────────────────────────────────────────
    public static final String ROUTING_INBOUND  = "inbound";
    public static final String ROUTING_OUTBOUND = "outbound";
    public static final String ROUTING_AUDIT    = "audit";

    // ── Exchange bean ─────────────────────────────────────────────────────────

    @Bean
    public DirectExchange ebmsExchange() {
        return ExchangeBuilder
                .directExchange(EXCHANGE_EBMS)
                .durable(true)
                .build();
    }

    // ── Queue beans ───────────────────────────────────────────────────────────

    @Bean
    public Queue inboundQueue() {
        return QueueBuilder.durable(QUEUE_INBOUND)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_DLQ)
                .build();
    }

    @Bean
    public Queue outboundQueue() {
        return QueueBuilder.durable(QUEUE_OUTBOUND)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_DLQ)
                .build();
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(QUEUE_AUDIT).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding inboundBinding(Queue inboundQueue, DirectExchange ebmsExchange) {
        return BindingBuilder.bind(inboundQueue).to(ebmsExchange).with(ROUTING_INBOUND);
    }

    @Bean
    public Binding outboundBinding(Queue outboundQueue, DirectExchange ebmsExchange) {
        return BindingBuilder.bind(outboundQueue).to(ebmsExchange).with(ROUTING_OUTBOUND);
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, DirectExchange ebmsExchange) {
        return BindingBuilder.bind(auditQueue).to(ebmsExchange).with(ROUTING_AUDIT);
    }

    // ── Jackson JSON message converter ────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
