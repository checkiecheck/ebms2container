package nl.logius.ebms.orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Houdt de {@code ebms_message}-status van een uitgaand bericht bij, onafhankelijk van of de
 * rest van de verwerking (signing/encryptie/verzenden) uiteindelijk slaagt.
 *
 * <p>Elke methode draait in haar EIGEN, direct-committende transactie
 * ({@code Propagation.REQUIRES_NEW}). Dit is bewust losgekoppeld van
 * {@code OutboundMessageService.handleOutboundMessage()}: zonder deze scheiding zou een fout
 * tijdens signing/encryptie/verzenden ook de zojuist geschreven {@code PROCESSING}-rij laten
 * verdwijnen, waardoor mislukte berichten nooit in {@code ebms_message}/de admin-UI verschijnen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageTrackingService {

    private final EbmsMessageRepository messageRepository;

    /**
     * Maakt de {@code ebms_message}-rij aan (status=PROCESSING) of werkt deze idempotent bij als
     * er al een rij bestaat voor deze {@code messageId}. Wordt twee keer aangeroepen: direct na
     * validatie (zonder {@code rawSoapXml}/{@code channel}, nog niet beschikbaar) en opnieuw
     * vlak voor het verzenden ("enrich", met de opgebouwde SOAP-envelop en het afleverkanaal).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EbmsMessageEntity createOrUpdateProcessing(
            String messageId,
            EbmsOutboundMessage message,
            EbxmlMessageHeader header,
            String rawSoapXml,
            DeliveryChannelDto channel) {

        String fromPartyId = header.getFrom() != null && !header.getFrom().isEmpty()
            ? header.getFrom().get(0).getValue() : "UNKNOWN";
        String toPartyId = header.getTo() != null && !header.getTo().isEmpty()
            ? header.getTo().get(0).getValue() : "UNKNOWN";
        Instant ttl = channel != null && channel.getPersistDuration() != null
            ? Instant.now().plusSeconds(channel.getPersistDuration()) : null;

        Optional<EbmsMessageEntity> existing = findExistingOutbound(messageId);
        if (existing.isPresent()) {
            EbmsMessageEntity entity = existing.get();
            entity.setRawSoapXml(rawSoapXml);
            entity.setPayloadRef(message.getPayloadRef());
            entity.setPayloadContentType(message.getPayloadContentType());
            entity.setTimeToLive(ttl);
            entity.setStatus(MessageStatus.PROCESSING);
            log.debug("[OUTBOUND] Idempotente herverwerking: messageId={}", messageId);
            return messageRepository.save(entity);
        }

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

        try {
            return messageRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            log.error("[OUTBOUND] Kon PROCESSING-bericht NIET persisteren: messageId={} bestaat al als bericht "
                + "met een andere richting (unique constraint op message_id) - dit duidt op een messageId-"
                + "botsing tussen INBOUND en OUTBOUND.", messageId);
            return entity;
        }
    }

    /** Zet de rij op SENT (rm-profielen, wacht op ACK) of DELIVERED (be-profielen). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSentOrDelivered(String messageId, boolean requireAck) {
        findExistingOutbound(messageId).ifPresentOrElse(entity -> {
            if (requireAck) {
                entity.setStatus(MessageStatus.SENT);
                entity.setAckRequested(true);
            } else {
                entity.setStatus(MessageStatus.DELIVERED);
            }
            messageRepository.save(entity);
        }, () -> log.warn("[OUTBOUND] Kon status niet bijwerken: geen ebms_message-rij voor messageId={}", messageId));
    }

    /**
     * Zet de rij op FAILED met foutdetail. Draait altijd in een eigen, direct-committende
     * transactie zodat dit overeind blijft ook al rolt de aanroepende flow verder niets terug.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String messageId, String errorMessage) {
        findExistingOutbound(messageId).ifPresentOrElse(entity -> {
            entity.setStatus(MessageStatus.FAILED);
            entity.setErrorMessage(errorMessage);
            messageRepository.save(entity);
        }, () -> log.warn("[OUTBOUND] Kon status niet op FAILED zetten: geen ebms_message-rij voor messageId={}", messageId));
    }

    private Optional<EbmsMessageEntity> findExistingOutbound(String messageId) {
        return messageRepository.findByMessageIdAndDirection(messageId, MessageDirection.OUTBOUND);
    }
}
