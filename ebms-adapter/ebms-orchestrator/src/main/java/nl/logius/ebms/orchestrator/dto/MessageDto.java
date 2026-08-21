package nl.logius.ebms.orchestrator.dto;

import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only DTO voor de admin message-monitor.
 * Voorkomt het lekken van JPA-entiteiten (en eventuele lazy-loading issues) via de REST-laag.
 */
public record MessageDto(
    UUID id,
    String messageId,
    String refToMessageId,
    String conversationId,
    String cpaId,
    String fromPartyId,
    String toPartyId,
    String service,
    String action,
    MessageDirection direction,
    MessageStatus status,
    Instant timestamp,
    short retryCount,
    Instant lastRetryAt,
    boolean ackRequested,
    String payloadContentType,
    String payloadRef,
    String rawSoapXml
) {
    public static MessageDto from(EbmsMessageEntity e) {
        return new MessageDto(
            e.getId(),
            e.getMessageId(),
            e.getRefToMessageId(),
            e.getConversationId(),
            e.getCpaId(),
            e.getFromPartyId(),
            e.getToPartyId(),
            e.getService(),
            e.getAction(),
            e.getDirection(),
            e.getStatus(),
            e.getTimestamp(),
            e.getRetryCount(),
            e.getLastRetryAt(),
            e.isAckRequested(),
            e.getPayloadContentType(),
            e.getPayloadRef(),
            e.getRawSoapXml()
        );
    }
}
