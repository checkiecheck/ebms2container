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
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.time.Instant;

/**
 * Asynchrone AMQP-consument voor uitgaande ebMS2-berichten.
 * Bevat de volledige crypto- en verzend-pipeline met robuuste foutafhandeling
 * en bescherming tegen "poison pill" requeue loops.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageService {

    private final EbmsMessageRepository ebmsMessageRepository;
    private final OutboundSoapClient outboundSoapClient;
    private final CryptoServiceClient cryptoServiceClient;
    private final CpaChannelCacheService cpaChannelCacheService;
    private final RabbitTemplate rabbitTemplate;
    private final SoapHelper soapHelper;

    @Value("${ebms.outbound.max-retries:5}")
    private int maxRetries;

    /**
     * Luistert naar uitgaande berichten op de RabbitMQ-queue.
     * Implementeert handmatige acks om requeue-gedrag nauwkeurig te sturen.
     */
    @RabbitListener(queues = RabbitMqConfig.QUEUE_OUTBOUND, ackMode = "MANUAL")
    public void handleOutboundMessage(EbmsOutboundMessage outboundMsg, Channel channel,
                                      @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("[OUTBOUND-CONSUMER] Ontvangen bericht messageId={} op queue", outboundMsg.getMessageId());

        EbmsMessageEntity entity = null;
        try {
            // 1. Idempotent persisteren van de uitgaande berichtstatus (voorkomt unique-constraint fouten)
            entity = persistOutboundMessage(outboundMsg);

            // 2. Berichtvalidatie (basic check ter voorkoming van poison pills)
            if (outboundMsg.getMessageId() == null || outboundMsg.getCpaId() == null) {
                log.error("[OUTBOUND-CONSUMER] Ongeldig AMQP bericht ontvangen (messageId of cpaId ontbreekt). Terminale fout.");
                markAsFailedAndAck(entity, outboundMsg.getMessageId(), "INVALID_AMQP_MESSAGE", channel, deliveryTag);
                return;
            }

            // 3. CPA Lookup voor het afleverkanaal (Splitsing transient vs terminale fouten ter voorkoming van requeue loop)
            DeliveryChannelDto deliveryChannel;
            try {
                deliveryChannel = cpaChannelCacheService.findDeliveryChannel(outboundMsg.getCpaId(), outboundMsg.getToPartyId());
                if (deliveryChannel == null) {
                    throw new EbmsException("CHANNEL_NOT_FOUND", "Geen geldig afleverkanaal gevonden voor CPA=" + outboundMsg.getCpaId());
                }
            } catch (EbmsException e) {
                if ("CHANNEL_NOT_FOUND".equals(e.getErrorCode())) {
                    // TERMINALE FOUT: CPA-Id of Party-Id is niet correct ingesteld.
                    log.error("[OUTBOUND-CONSUMER] CPA-lookup permanent gefaald: Afleverkanaal niet geconfigureerd. MessageId={}", outboundMsg.getMessageId(), e);
                    markAsFailedAndAck(entity, outboundMsg.getMessageId(), "CHANNEL_NOT_FOUND", channel, deliveryTag);
                    return;
                }
                // TRANSIËNTE FOUT (bijv. CPA_SERVICE_UNAVAILABLE): Laat deze doorstromen naar de outer catch voor retry/requeue!
                throw e;
            } catch (Exception e) {
                log.error("[OUTBOUND-CONSUMER] Onverwachte fout bij CPA-lookup voor messageId={}", outboundMsg.getMessageId(), e);
                throw e;
            }

            // 4. Crypto Pipeline (Signeren & Encryptie conform het ingestelde Digikoppeling profiel)
            EbxmlProfile profile = outboundMsg.getProfile();
            boolean requiresSigning = (profile == EbxmlProfile.OSB_BE_S || profile == EbxmlProfile.OSB_RM_S ||
                                       profile == EbxmlProfile.OSB_RM_E || profile == EbxmlProfile.OSB_BE_E);
            boolean requiresEncryption = (profile == EbxmlProfile.OSB_RM_E || profile == EbxmlProfile.OSB_BE_E);

            String processedSoapXml = entity.getRawSoapXml();
            try {
                if (requiresSigning) {
                    log.debug("[OUTBOUND-CONSUMER] Signeren van bericht messageId={}", outboundMsg.getMessageId());
                    processedSoapXml = cryptoServiceClient.sign(processedSoapXml, "orchestrator-key", outboundMsg.getMessageId());
                }
                if (requiresEncryption) {
                    log.debug("[OUTBOUND-CONSUMER] Encrypten van bericht messageId={}", outboundMsg.getMessageId());
                    processedSoapXml = cryptoServiceClient.encrypt(processedSoapXml, "orchestrator-key", outboundMsg.getMessageId());
                }
            } catch (Exception e) {
                log.error("[OUTBOUND-CONSUMER] Cryptografische verwerking mislukt voor messageId={}. Terminale fout.", outboundMsg.getMessageId(), e);
                markAsFailedAndAck(entity, outboundMsg.getMessageId(), "CRYPTO_PROCESSING_FAILED", channel, deliveryTag);
                return;
            }

            // 5. Verzenden van het bewerkte SOAP-bericht via CXF
            try {
                log.info("[OUTBOUND-CONSUMER] Versturen SOAP messageId={} naar endpoint={}", outboundMsg.getMessageId(), deliveryChannel.getEndpoint());
                
                // Converteer String XML terug naar SOAPMessage en verstuur
                SOAPMessage soapMessage = soapHelper.stringToSoap(processedSoapXml);
                outboundSoapClient.send(soapMessage, deliveryChannel.getEndpoint());

                // 6. Statusovergang bij succesvolle verzending
                boolean isReliable = (profile == EbxmlProfile.OSB_RM || profile == EbxmlProfile.OSB_RM_S || profile == EbxmlProfile.OSB_RM_E);
                if (isReliable) {
                    entity.setStatus(MessageStatus.SENT); // Wachten op asynchrone ACK
                } else {
                    entity.setStatus(MessageStatus.DELIVERED); // Best effort, direct gereed
                }
                entity.setUpdatedAt(Instant.now());
                ebmsMessageRepository.save(entity);

                // Publiceer succes audit-event
                publishAuditEvent(entity, "MESSAGE_SENT");

                // Bevestig berichtontvangst bij RabbitMQ (ack)
                channel.basicAck(deliveryTag, false);
                log.info("[OUTBOUND-CONSUMER] Outbound pipeline succesvol afgerond voor messageId={}, status={}", outboundMsg.getMessageId(), entity.getStatus());

            } catch (Exception e) {
                // 7. Netwerk/Verzendfout afhandeling met retry-scheduler integratie
                log.warn("[OUTBOUND-CONSUMER] Netwerkfout bij verzenden messageId={}, start retry-afhandeling", outboundMsg.getMessageId(), e);
                handleOutboundFailure(entity, outboundMsg.getMessageId(), channel, deliveryTag);
            }

        } catch (Exception e) {
            log.error("[OUTBOUND-CONSUMER] Onverwachte fout in outbound consumer voor messageId={}", outboundMsg.getMessageId(), e);
            if (entity != null) {
                handleOutboundFailure(entity, outboundMsg.getMessageId(), channel, deliveryTag);
            } else {
                // Als persisteren zelf faalt, nack met requeue=false om deadlock te vermijden
                try {
                    channel.basicNack(deliveryTag, false, false);
                } catch (IOException ioe) {
                    log.error("Failed to nack message", ioe);
                }
            }
        }
    }

    /**
     * Idempotente insert-of-update operatie om unique-constraint fouten te voorkomen.
     */
    @Transactional
    public EbmsMessageEntity persistOutboundMessage(EbmsOutboundMessage outboundMsg) {
        return ebmsMessageRepository.findByMessageId(outboundMsg.getMessageId())
            .map(existing -> {
                log.info("[OUTBOUND] Bestaand bericht messageId={} gevonden in DB, status wordt bijgewerkt", outboundMsg.getMessageId());
                existing.setUpdatedAt(Instant.now());
                return ebmsMessageRepository.save(existing);
            })
            .orElseGet(() -> {
                log.info("[OUTBOUND] Nieuwe database record aanmaken voor messageId={}", outboundMsg.getMessageId());
                EbmsMessageEntity entity = new EbmsMessageEntity();
                entity.setMessageId(outboundMsg.getMessageId());
                entity.setConversationId(outboundMsg.getConversationId());
                entity.setCpaId(outboundMsg.getCpaId());
                entity.setService(outboundMsg.getService());
                entity.setServiceType(outboundMsg.getServiceType());
                entity.setAction(outboundMsg.getAction());
                entity.setFromPartyId(outboundMsg.getFromPartyId());
                entity.setFromPartyType(outboundMsg.getFromPartyType());
                entity.setFromRole(outboundMsg.getFromRole());
                entity.setToPartyId(outboundMsg.getToPartyId());
                entity.setToPartyType(outboundMsg.getToPartyType());
                entity.setToRole(outboundMsg.getToRole());
                entity.setTimestamp(outboundMsg.getTimestamp());
                entity.setTimeToLive(outboundMsg.getTimeToLive());
                entity.setRawSoapXml(outboundMsg.getRawSoapXml());
                entity.setDirection(MessageDirection.OUTBOUND);
                entity.setStatus(MessageStatus.PROCESSING);
                entity.setRetryCount(0);
                entity.setAckRequested(outboundMsg.isAckRequested());
                entity.setDuplicateElimination(outboundMsg.isDuplicateElimination());
                entity.setPayloadRef(outboundMsg.getPayloadRef());
                entity.setPayloadContentType(outboundMsg.getPayloadContentType());
                entity.setCreatedAt(Instant.now());
                entity.setUpdatedAt(Instant.now());
                return ebmsMessageRepository.save(entity);
            });
    }

    /**
     * Handelt netwerk-/transiënte fouten af. Verhoogt de retry-count en requeued indien mogelijk.
     */
    private void handleOutboundFailure(EbmsMessageEntity entity, String messageId, Channel channel, long deliveryTag) {
        try {
            int currentRetry = entity.getRetryCount() != null ? entity.getRetryCount() : 0;
            if (currentRetry >= maxRetries) {
                log.error("[OUTBOUND] Maximaal aantal retries ({}) bereikt voor messageId={}. Status wordt FAILED.", maxRetries, messageId);
                entity.setStatus(MessageStatus.FAILED);
                entity.setUpdatedAt(Instant.now());
                ebmsMessageRepository.save(entity);
                publishAuditEvent(entity, "MESSAGE_FAILED_MAX_RETRIES");
                channel.basicAck(deliveryTag, false); // Definitief verwijderen uit queue
            } else {
                entity.setRetryCount(currentRetry + 1);
                entity.setLastRetryAt(Instant.now());
                entity.setUpdatedAt(Instant.now());
                ebmsMessageRepository.save(entity);

                // Requeue het bericht op de queue voor een volgende poging
                channel.basicNack(deliveryTag, false, true);
                log.info("[OUTBOUND] Bericht requeued voor retry (poging {}/{})", currentRetry + 1, maxRetries);
            }
        } catch (Exception ex) {
            log.error("Fout tijdens foutverwerking voor messageId={}", messageId, ex);
        }
    }

    /**
     * Markeert het bericht als terminal FAILED, publiceert een audit event en bevestigt ontvangst om requeuing te stoppen.
     */
    private void markAsFailedAndAck(EbmsMessageEntity entity, String messageId, String errorReason, Channel channel, long deliveryTag) throws IOException {
        if (entity != null) {
            entity.setStatus(MessageStatus.FAILED);
            entity.setUpdatedAt(Instant.now());
            ebmsMessageRepository.save(entity);
            publishAuditEvent(entity, errorReason);
        }
        channel.basicAck(deliveryTag, false);
        log.warn("[OUTBOUND] Bericht messageId={} definitief afgekeurd (fout: {}), ack verstuurd om requeue te voorkomen.", messageId, errorReason);
    }

    /**
     * Hulpmethode om append-only audit events te publiceren op de AMQP exchange.\n     */
    private void publishAuditEvent(EbmsMessageEntity entity, String eventType) {
        try {
            AuditEvent event = new AuditEvent();
            event.setMessageId(entity.getMessageId());
            event.setCpaId(entity.getCpaId());
            event.setDirection(entity.getDirection().name());
            event.setStatus(entity.getStatus().name());
            event.setTimestamp(Instant.now());
            event.setEventType(eventType);
            rabbitTemplate.convertAndSend("ebms.audit.events", "", event);
            log.debug("[AUDIT-LOG] Event '{}' gepubliceerd voor messageId={}", eventType, entity.getMessageId());
        } catch (Exception e) {
            log.error("Fout bij publiceren van audit-event '{}' voor messageId={}", eventType, entity.getMessageId(), e);
        }
    }
}
