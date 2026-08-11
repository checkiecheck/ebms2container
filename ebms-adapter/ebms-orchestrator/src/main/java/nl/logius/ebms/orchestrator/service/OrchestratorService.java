package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.DuplicateMessageException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsInboundMessage;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.config.RetryProperties;
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
    private final CpaValidationService  cpaValidationService;
    private final RetryProperties       retryProperties;

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

        // 1. CPA-validatie via cpa-service (fail-closed)
        CpaValidationResult cpaResult = cpaValidationService.validateCpaAndOin(cpaId, clientOin);
        if (!cpaResult.isValid()) {
            log.warn("[CPA-BLOCKED] messageId={} reden={}", messageId, cpaResult.getErrorMessage());
            publishAudit(AuditEvent.builder()
                .eventType("MESSAGE_REJECTED")
                .messageId(messageId)
                .cpaId(cpaId)
                .partyId(clientOin)
                .result("FAILURE")
                .errorDetail(cpaResult.getErrorMessage())
                .build());
            throw new EbmsException("CPA_VALIDATION_FAILED", cpaResult.getErrorMessage());
        }

        // 2. Duplicate suppression
        if (messageRepository.existsByMessageId(messageId)) {
            log.warn("[DUPLICATE] messageId={}", messageId);
            persistDuplicate(messageId, header);
            throw new DuplicateMessageException(messageId);
        }

        // 3. Persisteer in database
        EbmsMessageEntity entity = buildEntity(header, rawSoap, clientOin);
        entity = messageRepository.save(entity);

        // 4. Publiceer op AMQP inbound queue
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

        // 5. Publiceer audit-event
        publishAudit(AuditEvent.builder()
            .eventType("MESSAGE_RECEIVED")
            .messageId(messageId)
            .conversationId(conversationId)
            .cpaId(cpaId)
            .partyId(clientOin)
            .action(header.getAction())
            .result("SUCCESS")
            .build());

        // 6. Update status naar PROCESSING
        entity.setStatus(MessageStatus.PROCESSING);
        messageRepository.save(entity);

        // 7. Construeer en retourneer SOAP ACK (alleen bij rm-profielen)
        boolean needsAck = header.getAckRequested() != null;
        if (needsAck) {
            return soapHelper.createAck(header);
        }
        return soapHelper.createEmptyResponse();
    }

    // ── Scheduled taken ───────────────────────────────────────────────────

    /**
     * Verwerkt een inkomende ebMS2 {@code Acknowledgment} (ACK).
     *
     * <p>Reliable Messaging status-machine:
     * Zoekt het originele bericht via {@code RefToMessageId} op en zet de status
     * van {@code SENT} naar {@code DELIVERED}. Pas op dit moment is het bericht
     * als definitief afgeleverd beschouwd conform Digikoppeling Koppelvlakstandaard.
     *
     * @param refToMessageId het {@code RefToMessageId} uit de inkomende ACK-header
     * @return SOAP response (leeg 200 OK)
     */
    @Transactional
    public SOAPMessage handleAcknowledgment(String refToMessageId) {
        log.info("[ACK] Acknowledgment ontvangen voor messageId={}", refToMessageId);

        messageRepository.findByMessageIdAndStatus(refToMessageId, MessageStatus.SENT)
            .ifPresentOrElse(entity -> {
                entity.setStatus(MessageStatus.DELIVERED);
                messageRepository.save(entity);
                log.info("[ACK] Bericht {} bijgewerkt naar DELIVERED", refToMessageId);

                publishAudit(AuditEvent.builder()
                    .eventType("MESSAGE_ACKNOWLEDGED")
                    .messageId(refToMessageId)
                    .conversationId(entity.getConversationId())
                    .cpaId(entity.getCpaId())
                    .result("SUCCESS")
                    .build());
            }, () -> {
                // Kan al DELIVERED zijn (dubbele ACK) of nooit verzonden zijn – log alleen
                log.warn("[ACK] Geen SENT-bericht gevonden voor refToMessageId={}", refToMessageId);
            });

        return soapHelper.createEmptyResponse();
    }
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

    /**
     * Reliable Messaging retry-scheduler.
     *
     * <p>Herneemt het verzenden van FAILED berichten die nog retrypogingen over hebben.
     * Interval: configureerbaar via {@code ebms.reliable-messaging.retry-check-interval-ms}.
     */
    @Scheduled(fixedDelayString = "${ebms.reliable-messaging.retry-check-interval-ms:300000}")
    @Transactional
    public void retryFailedMessages() {
        Instant retryBefore = Instant.now()
            .minusSeconds(retryProperties.getRetryIntervalSeconds());

        List<EbmsMessageEntity> candidates = messageRepository.findMessagesForRetry(
            retryProperties.getMaxRetries(), retryBefore);

        if (candidates.isEmpty()) {
            return;
        }

        log.info("[RETRY] {} kandidaat-bericht(en) gevonden voor herpoging", candidates.size());

        for (EbmsMessageEntity msg : candidates) {
            try {
                msg.setRetryCount((short) (msg.getRetryCount() + 1));
                msg.setLastRetryAt(Instant.now());
                msg.setStatus(MessageStatus.PROCESSING);
                messageRepository.save(msg);

                EbmsOutboundMessage retryMsg = EbmsOutboundMessage.builder()
                    .messageId(msg.getMessageId())
                    .scheduledAt(Instant.now())
                    .build();
                rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE_EBMS,
                    RabbitMqConfig.ROUTING_OUTBOUND,
                    retryMsg);

                log.info("[RETRY] Herpoging #{} gepubliceerd: messageId={}",
                    msg.getRetryCount(), msg.getMessageId());

            } catch (Exception e) {
                log.error("[RETRY] Herpoging mislukt voor messageId={}: {}",
                    msg.getMessageId(), e.getMessage());
            }
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
