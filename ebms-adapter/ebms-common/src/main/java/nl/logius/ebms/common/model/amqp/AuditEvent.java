package nl.logius.ebms.common.model.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

/**
 * AMQP audit-event bericht.
 * Gepubliceerd door orchestrator en crypto-service op queue {@code ebms.audit.events}.
 * Verwerkt door de toekomstige {@code auditor-service} (Fase 3).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEvent {

    /**
     * Type van de gebeurtenis.
     * Bijv: MESSAGE_RECEIVED | MESSAGE_DELIVERED | MESSAGE_FAILED |
     *       SIGNATURE_VERIFIED | ENCRYPTION_APPLIED | CPA_LOOKED_UP
     */
    private String  eventType;
    private String  messageId;
    private String  conversationId;
    private String  cpaId;
    private String  partyId;
    private String  action;

    /** SUCCESS | FAILURE */
    private String  result;

    /** Foutdetail bij FAILURE. */
    private String  errorDetail;

    /** Tijdstip van de gebeurtenis (UTC). */
    @Builder.Default
    private Instant occurredAt = Instant.now();
}
