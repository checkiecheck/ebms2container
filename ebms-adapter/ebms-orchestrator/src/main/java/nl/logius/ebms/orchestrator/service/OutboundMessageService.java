package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.EbxmlProfile;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;

/**
 * Asynchrone AMQP-consument voor uitgaande ebMS2-berichten.
 *
 * <p>Luistert op de {@code ebms.outbound.messages} queue en implementeert de
 * volledige crypto- en verzend-pipeline:
 * <ol>
 *   <li>CPA + afleverkanaal opzoeken (gecached)</li>
 *   <li>Signing (bij profielen: osb-be-s, osb-rm-s, osb-be-e, osb-rm-e)</li>
 *   <li>Encryptie (bij profielen: osb-be-e, osb-rm-e)</li>
 *   <li>Verzenden via {@link OutboundSoapClient} (CXF Dispatch)</li>
 *   <li>Status-machine: BE→DELIVERED, RM→SENT (wacht op ACK)</li>
 * </ol>
 *
 * <p>Reliable Messaging status-machine:
 * <ul>
 *   <li>Best Effort (osb-be, osb-be-s, osb-be-e): HTTP 200 → {@code DELIVERED}</li>
 *   <li>Reliable Messaging (osb-rm, osb-rm-s, osb-rm-e): HTTP 200 → {@code SENT}
 *       (definitief DELIVERED pas na ontvangst ACK via {@link OrchestratorService#handleAcknowledgment})</li>
 *   <li>Bij fout: nack → retry via RabbitMQ of scheduler → FAILED na maxRetries</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageService {

    private final EbmsMessageRepository messageRepository;
    private final CpaChannelCacheService cpaChannelCacheService;
    private final CryptoServiceClient   cryptoServiceClient;
    private final OutboundSoapClient    outboundSoapClient;
    private final SoapHelper            soapHelper;
    private final RabbitTemplate        rabbitTemplate;

    @Value("${ebms.outbound.signing-key-alias:signing-key}")
    private String defaultSigningKeyAlias;

    // ── AMQP Listener ─────────────────────────────────────────────────────────

    /**
     * Verwerkt een uitgaand ebMS2-bericht van de outbound-queue.
     *
     * @param message     deserialized {@link EbmsOutboundMessage} van de backoffice
     * @param amqpChannel RabbitMQ kanaal voor manual ack/nack
     * @param deliveryTag AMQP delivery tag voor ack/nack
     */
    @RabbitListener(queues = RabbitMqConfig.QUEUE_OUTBOUND)
    @Transactional
    public void handleOutboundMessage(
            EbmsOutboundMessage message,
            Channel amqpChannel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        String messageId = message.getMessageId();
        log.info("[OUTBOUND] Verwerken: messageId={}", messageId);

        try {
            EbxmlMessageHeader header = message.getHeader();
            if (header == null || header.getCpaId() == null) {
                log.error("[OUTBOUND] Ongeldig bericht: messageId={} – ontbrekende header", messageId);
                nack(amqpChannel, deliveryTag, false); // gooi weg, niet opnieuw proberen
                return;
            }

            String cpaId     = header.getCpaId();
            String toPartyId = extractToPartyId(header);

            // ── 1. Afleverkanaal ophalen (gecached via CpaChannelCacheService) ─
            DeliveryChannelDto channel = cpaChannelCacheService.getChannel(cpaId, toPartyId);
            EbxmlProfile       profile = EbxmlProfile.fromCode(channel.getDkProfile());
            boolean            requireAck = profile.hasReliableMessaging();

            // ── 2. SOAP-envelop opbouwen ──────────────────────────────────
            SOAPMessage soapMsg    = soapHelper.buildOutboundSoap(header, requireAck);
            String      rawSoapXml = soapHelper.soapToString(soapMsg);

            // ── 3. Signing (indien vereist door profiel) ───────────────────
            if (profile.requiresSigning()) {
                String signingAlias = defaultSigningKeyAlias;
                log.debug("[OUTBOUND] Signing: messageId={} alias={}", messageId, signingAlias);
                rawSoapXml = cryptoServiceClient.sign(rawSoapXml, signingAlias, messageId);
            }

            // ── 4. Encryptie (indien vereist door profiel) ─────────────────
            if (profile.requiresEncryption()) {
                String recipientAlias = toPartyId; // alias = OIN/partyId van ontvanger
                log.debug("[OUTBOUND] Versleutelen: messageId={} recipient={}", messageId, recipientAlias);
                rawSoapXml = cryptoServiceClient.encrypt(rawSoapXml, recipientAlias, messageId);
            }

            // ── 5. Persisteer bericht in database (status=PROCESSING) ──────
            EbmsMessageEntity entity = persistOutboundMessage(message, header, rawSoapXml, channel);

            // ── 6. Versturen via CXF SOAP-client ──────────────────────────
            outboundSoapClient.send(channel.getEndpointUrl(), rawSoapXml);

            // ── 7. Status-machine bijwerken ────────────────────────────────
            if (requireAck) {
                // Reliable Messaging: SENT – wacht op ebMS2 Acknowledgment
                entity.setStatus(MessageStatus.SENT);
                entity.setAckRequested(true);
                log.info("[OUTBOUND] Verzonden (RM) – wacht op ACK: messageId={}", messageId);
            } else {
                // Best Effort: direct DELIVERED
                entity.setStatus(MessageStatus.DELIVERED);
                log.info("[OUTBOUND] Verzonden (BE) – DELIVERED: messageId={}", messageId);
            }
            messageRepository.save(entity);

            // ── 8. Audit-event publiceren ──────────────────────────────────
            publishAudit(AuditEvent.builder()
                .eventType("MESSAGE_SENT")
                .messageId(messageId)
                .conversationId(header.getConversationId())
                .cpaId(cpaId)
                .action(header.getAction())
                .result("SUCCESS")
                .build());

            // ── 9. AMQP ACK ───────────────────────────────────────────────
            ack(amqpChannel, deliveryTag);

        } catch (EbmsException e) {
            log.error("[OUTBOUND] EbmsException: messageId={} code={} msg={}",
                messageId, e.getErrorCode(), e.getMessage());
            nack(amqpChannel, deliveryTag, true); // requeue voor retry

        } catch (Exception e) {
            log.error("[OUTBOUND] Onverwachte fout: messageId={}", messageId, e);
            nack(amqpChannel, deliveryTag, true); // requeue voor retry
        }
    }

    // ── Interne helpers ───────────────────────────────────────────────────────

    private EbmsMessageEntity persistOutboundMessage(
            EbmsOutboundMessage message,
            EbxmlMessageHeader  header,
            String              rawSoapXml,
            DeliveryChannelDto  channel) {

        String fromPartyId = header.getFrom() != null && !header.getFrom().isEmpty()
            ? header.getFrom().get(0).getValue() : "UNKNOWN";
        String toPartyId   = header.getTo() != null && !header.getTo().isEmpty()
            ? header.getTo().get(0).getValue() : "UNKNOWN";
        String messageId   = message.getMessageId();

        // ── Idempotente upsert: bij retry hetzelfde bericht bijwerken ──────────
        // Zonder dit levert een requeue-cyclus een unique-constraint fout op
        // (messageId-kolom is unique=true in EbmsMessageEntity).
        return messageRepository.findByMessageId(messageId)
            .map(existing -> {
                // Bijwerken wat kan zijn gewijzigd (bijv. nieuw gesigneerde/versleutelde SOAP)
                existing.setRawSoapXml(rawSoapXml);
                existing.setPayloadRef(message.getPayloadRef());
                existing.setPayloadContentType(message.getPayloadContentType());
                existing.setStatus(MessageStatus.PROCESSING);
                log.debug("[OUTBOUND] Idempotente herverwerking: messageId={}", messageId);
                return messageRepository.save(existing);
            })
            .orElseGet(() -> {
                // TTL berekenen uit CPA persistDuration (in seconden)
                Instant ttl = channel.getPersistDuration() != null
                    ? Instant.now().plusSeconds(channel.getPersistDuration()) : null;

                EbmsMessageEntity entity = EbmsMessageEntity.builder()
                    .messageId(messageId)
                    .conversationId(header.getConversationId())
                    .cpaId(header.getCpaId())
                    .fromPartyId(fromPartyId)
                    .toPartyId(toPartyId)
                    .fromRole(header.getFromRole())
                    .toRole(header.getToRole())
                    .service(header.getService() != null ? header.getService().getValue() : "UNKNOWN")
                    .serviceType(header.getService() != null ? header.getService().getType() : null)
                    .action(header.getAction())
                    .direction(MessageDirection.OUTBOUND)
                    .status(MessageStatus.PROCESSING)
                    .timestamp(Instant.now())
                    .timeToLive(ttl)
                    .payloadRef(message.getPayloadRef())
                    .payloadContentType(message.getPayloadContentType())
                    .rawSoapXml(rawSoapXml)
                    .build();

                return messageRepository.save(entity);
            });
    }

    private String extractToPartyId(EbxmlMessageHeader header) {
        if (header.getTo() != null && !header.getTo().isEmpty()) {
            return header.getTo().get(0).getValue();
        }
        throw new EbmsException("INVALID_HEADER", "To-partij ontbreekt in ebXML MessageHeader");
    }

    private void publishAudit(AuditEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_EBMS,
                RabbitMqConfig.ROUTING_AUDIT,
                event);
        } catch (Exception e) {
            log.warn("[OUTBOUND] Audit-event kon niet gepubliceerd worden: {}", e.getMessage());
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("[OUTBOUND] AMQP ACK mislukt: {}", e.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception e) {
            log.warn("[OUTBOUND] AMQP NACK mislukt: {}", e.getMessage());
        }
    }
}
