package nl.logius.ebms.orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        return findExistingInbound(messageId)
            .map(existing -> {
                existing.setRawSoapXml(processedSoap);
                existing.setStatus(MessageStatus.RECEIVED);
                return messageRepository.save(existing);
            })
            .orElseGet(() -> messageRepository.save(buildEntity(header, processedSoap, clientOin)));
    }

    /** Zet de rij op DELIVERED nadat deze succesvol op de inbound-queue is gepubliceerd. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDelivered(String messageId) {
        findExistingInbound(messageId).ifPresentOrElse(entity -> {
            entity.setStatus(MessageStatus.DELIVERED);
            messageRepository.save(entity);
        }, () -> log.warn("[INBOUND] Kon status niet bijwerken naar DELIVERED: geen rij voor messageId={}", messageId));
    }

    /**
     * Registreert een gedetecteerd duplicaat op het bestaande bericht, zonder de originele rij te
     * overschrijven (status/content blijven ongewijzigd - zie {@link MessageStatus#DUPLICATE}
     * javadoc en {@code uq_message_id}-constraint).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDuplicate(String messageId) {
        findExistingInbound(messageId).ifPresentOrElse(entity -> {
            entity.setDuplicateCount(entity.getDuplicateCount() + 1);
            entity.setLastDuplicateAt(Instant.now());
            messageRepository.save(entity);
            log.info("[INBOUND] Duplicaat geregistreerd (totaal {}x): messageId={}",
                entity.getDuplicateCount(), messageId);
        }, () -> log.warn("[INBOUND] Duplicaat gedetecteerd maar geen bestaande INBOUND-rij gevonden: messageId={}", messageId));
    }

    /**
     * Persisteert een afgewezen/mislukt inbound-bericht als FAILED met foutdetail. Idempotente
     * upsert; draait altijd in een eigen, direct-committende transactie zodat dit overeind blijft
     * ook al rolt de aanroepende flow (die hierna een exception doorgooit) verder niets terug.
     *
     * <p>{@code message_id} is globaal uniek in de database (niet per richting). Als hetzelfde
     * messageId al bestaat als OUTBOUND-rij (bv. een loopback-testscenario) kan hier geen nieuwe
     * INBOUND-rij ingevoegd worden - dat geven we dan als duidelijke ERROR-log door i.p.v. de
     * OUTBOUND-rij stilletjes te overschrijven (de oorspronkelijke bug: een FAILED-markering die
     * later weer door de OUTBOUND-flow werd overschreven met DELIVERED).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistFailed(EbxmlMessageHeader header, String rawSoap, String clientOin, String errorMessage) {
        String messageId = header.getMessageInfo() != null ? header.getMessageInfo().getMessageId() : null;
        if (messageId == null || messageId.isBlank()) {
            log.warn("[INBOUND] Kon FAILED-bericht niet persisteren: messageId ontbreekt in header");
            return;
        }
        Optional<EbmsMessageEntity> existing = findExistingInbound(messageId);
        if (existing.isPresent()) {
            EbmsMessageEntity entity = existing.get();
            entity.setStatus(MessageStatus.FAILED);
            entity.setErrorMessage(errorMessage);
            messageRepository.save(entity);
            return;
        }
        try {
            EbmsMessageEntity entity = buildEntity(header, rawSoap, clientOin);
            entity.setStatus(MessageStatus.FAILED);
            entity.setErrorMessage(errorMessage);
            messageRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.error("[INBOUND] Kon FAILED-bericht NIET persisteren: messageId={} bestaat al als bericht met "
                + "een andere richting (unique constraint op message_id) - de bestaande rij is NIET "
                + "overschreven. Oorspronkelijke reden voor afwijzing: {}", messageId, errorMessage);
        }
    }

    private Optional<EbmsMessageEntity> findExistingInbound(String messageId) {
        return messageRepository.findByMessageIdAndDirection(messageId, MessageDirection.INBOUND);
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
