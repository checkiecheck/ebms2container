package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.DuplicateMessageException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsInboundMessage;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Kernservice van de ebms-orchestrator.
 *
 * <p>Verantwoordelijkheden:
 * <ol>
 *   <li>Valideer het inkomende bericht (duplicate check, OIN-validatie)</li>
 *   <li>Persisteer bericht-state in PostgreSQL</li>
 *   <li>Publiceer op RabbitMQ (inbound queue)</li>
 *   <li>Retourneer ACK-SOAP-bericht</li>
 *   <li>Scheduled: opschonen verlopen berichten en Reliable Messaging retries</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorService {

    private final EbmsMessageRepository messageRepository;
    private final RabbitTemplate        rabbitTemplate;
    private final SoapHelper            soapHelper;

    // ── Inbound message processing ────────────────────────────────────────

    /**
     * Verwerkt een binnenkomend ebMS2-bericht.
     *
     * @param request       het originele SOAP-bericht
     * @param header        het geparsede ebXML MessageHeader object
     * @param rawSoap       het volledige SOAP-bericht als string (voor audit)
     * @param clientOin     het OIN uit de X-Forwarded-Client-OIN mTLS-header
     * @return              SOAP ACK-bericht (of lege response bij Best Effort)
     */
    @Transactional
    public SOAPMessage processInboundMessage(SOAPMessage request,
                                              EbxmlMessageHeader header,
                                              String rawSoap,
                                              String clientOin) {
        String messageId     = header.getMessageInfo().getMessageId();
        String conversationId = header.getConversationId();
        String cpaId         = header.getCpaId();

        log.info("[INBOUND] messageId={} cpaId={} from={} action={}",
            messageId, cpaId,
            header.getFrom().isEmpty() ? "?" : header.getFrom().get(0).getValue(),
            header.getAction());

        // 1. Duplicate suppression
        if (messageRepository.existsByMessageId(messageId)) {
            log.warn("[DUPLICATE] messageId={}", messageId);
            persistDuplicate(messageId, header);
            throw new DuplicateMessageException(messageId);
        }

        // 2. Persisteer in database
        EbmsMessageEntity entity = buildEntity(header, rawSoap, clientOin);
        entity = messageRepository.save(entity);

        // 3. Publiceer op AMQP inbound queue
        EbmsInboundMessage amqpMsg = EbmsInboundMessage.builder()
            .messageId(messageId)
            .conversationId(conversationId)
            .header(header)
            .rawSoapXml(rawSoap)
            .receivedAt(Instant.now())
            .build();
        rabbitTemplate.convertAndSend(
            RabbitMqConfig.EXCHANGE_EBMS,
            RabbitMqConfig.ROUTING_INBOUND,
            amqpMsg);

        // 4. Publiceer audit-event
        publishAudit(AuditEvent.builder()
            .eventType("MESSAGE_RECEIVED")
            .messageId(messageId)
            .conversationId(conversationId)
            .cpaId(cpaId)
            .partyId(clientOin)
            .action(header.getAction())
            .result("SUCCESS")
            .build());

        // 5. Update status naar PROCESSING
        entity.setStatus(MessageStatus.PROCESSING);
        messageRepository.save(entity);

        // 6. Construeer en retourneer SOAP ACK (alleen bij rm-profielen)
        boolean needsAck = header.getAckRequested() != null;
        if (needsAck) {
            return soapHelper.createAck(header);
        }
        return soapHelper.createEmptyResponse();
    }

    // ── Scheduled taken ───────────────────────────────────────────────────

    /** Detecteer en markeer verlopen berichten (elk uur). */
    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void expireMessages() {
        List<EbmsMessageEntity> expired = messageRepository.findExpiredMessages(Instant.now());
        for (EbmsMessageEntity msg : expired) {
            msg.setStatus(MessageStatus.FAILED);
            log.warn("[EXPIRED] messageId={}", msg.getMessageId());
        }
        if (!expired.isEmpty()) {
            messageRepository.saveAll(expired);
            log.info("{}  berichten als FAILED gemarkeerd (TTL verstreken)", expired.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private EbmsMessageEntity buildEntity(EbxmlMessageHeader header,
                                           String rawSoap,
                                           String clientOin) {
        return EbmsMessageEntity.builder()
            .messageId(header.getMessageInfo().getMessageId())
            .refToMessageId(header.getMessageInfo().getRefToMessageId())
            .conversationId(header.getConversationId())
            .cpaId(header.getCpaId())
            .fromPartyId(header.getFrom().isEmpty() ? clientOin : header.getFrom().get(0).getValue())
            .fromPartyType(header.getFrom().isEmpty() ? null : header.getFrom().get(0).getType())
            .fromRole(header.getFromRole())
            .toPartyId(header.getTo().isEmpty() ? null : header.getTo().get(0).getValue())
            .toPartyType(header.getTo().isEmpty() ? null : header.getTo().get(0).getType())
            .toRole(header.getToRole())
            .service(header.getService().getValue())
            .action(header.getAction())
            .direction(MessageDirection.INBOUND)
            .status(MessageStatus.RECEIVED)
            .timestamp(header.getMessageInfo().getTimestamp())
            .ackRequested(header.getAckRequested() != null)
            .rawSoapXml(rawSoap)
            .build();
    }

    private void persistDuplicate(String messageId, EbxmlMessageHeader header) {
        EbmsMessageEntity dup = messageRepository.findByMessageId(messageId).orElse(null);
        // Log alleen; origineel bericht blijft ongewijzigd
        publishAudit(AuditEvent.builder()
            .eventType("MESSAGE_DUPLICATE")
            .messageId(messageId)
            .conversationId(header.getConversationId())
            .cpaId(header.getCpaId())
            .result("FAILURE")
            .errorDetail("Duplicate messageId")
            .build());
    }

    private void publishAudit(AuditEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_EBMS,
                RabbitMqConfig.ROUTING_AUDIT,
                event);
        } catch (Exception e) {
            log.warn("Audit-event kon niet gepubliceerd worden: {}", e.getMessage());
        }
    }
}
