package nl.logius.ebms.orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Houdt de {@code ebms_message}-status van een inkomend bericht bij, onafhankelijk van of de
 * rest van de validatie/verwerking (OIN-antispoofing, CPA-validatie, decryptie,
 * handtekeningverificatie) uiteindelijk slaagt.
 *
 * <p>Elke methode draait in haar EIGEN, direct-committende transactie
 * ({@code Propagation.REQUIRES_NEW}) — losgekoppeld van {@code OrchestratorService
 * .processInboundMessage()}. Zonder deze scheiding zou een afgewezen/mislukt bericht (spoofing
 * gedetecteerd, onbekende CPA, decryptie- of handtekeningfout) geen enkel spoor achterlaten in
 * {@code ebms_message}/de admin-UI, omdat de aanroepende methode onder één {@code @Transactional}
 * stond en elke exception alles terugrolde (zelfde architecturale fout als eerder gefixt voor
 * outbound-berichten, zie {@link OutboundMessageTrackingService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundMessageTrackingService {

    private final EbmsMessageRepository messageRepository;

    /** Persisteert het succesvol gevalideerde bericht (status=RECEIVED). Idempotente upsert. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EbmsMessageEntity persistReceived(EbxmlMessageHeader header, String processedSoap, String clientOin) {
        String messageId = header.getMessageInfo().getMessageId();
        return messageRepository.findByMessageId(messageId)
            .map(existing -> {
                existing.setRawSoapXml(processedSoap);
                existing.setStatus(MessageStatus.RECEIVED);
                return messageRepository.save(existing);
            })
            .orElseGet(() -> messageRepository.save(buildEntity(header, processedSoap, clientOin)));
    }

    /** Zet de rij op PROCESSING nadat deze succesvol op de inbound-queue is gepubliceerd. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(String messageId) {
        messageRepository.findByMessageId(messageId).ifPresentOrElse(entity -> {
            entity.setStatus(MessageStatus.PROCESSING);
            messageRepository.save(entity);
        }, () -> log.warn("[INBOUND] Kon status niet bijwerken naar PROCESSING: geen rij voor messageId={}", messageId));
    }

    /**
     * Persisteert een afgewezen/mislukt inbound-bericht als FAILED met foutdetail. Idempotente
     * upsert; draait altijd in een eigen, direct-committende transactie zodat dit overeind blijft
     * ook al rolt de aanroepende flow (die hierna een exception doorgooit) verder niets terug.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistFailed(EbxmlMessageHeader header, String rawSoap, String clientOin, String errorMessage) {
        String messageId = header.getMessageInfo() != null ? header.getMessageInfo().getMessageId() : null;
        if (messageId == null || messageId.isBlank()) {
            log.warn("[INBOUND] Kon FAILED-bericht niet persisteren: messageId ontbreekt in header");
            return;
        }
        messageRepository.findByMessageId(messageId)
            .map(existing -> {
                existing.setStatus(MessageStatus.FAILED);
                existing.setErrorMessage(errorMessage);
                return messageRepository.save(existing);
            })
            .orElseGet(() -> {
                EbmsMessageEntity entity = buildEntity(header, rawSoap, clientOin);
                entity.setStatus(MessageStatus.FAILED);
                entity.setErrorMessage(errorMessage);
                return messageRepository.save(entity);
            });
    }

    private EbmsMessageEntity buildEntity(EbxmlMessageHeader header, String rawSoap, String clientOin) {
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
}
