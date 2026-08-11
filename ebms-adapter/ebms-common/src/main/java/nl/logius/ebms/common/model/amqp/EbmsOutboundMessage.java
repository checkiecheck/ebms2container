package nl.logius.ebms.common.model.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;

import java.time.Instant;

/**
 * AMQP-bericht voor uitgaande ebMS2-berichten.
 * Gepubliceerd door de backoffice op queue {@code ebms.outbound.messages};
 * afgehandeld door {@code ebms-orchestrator}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbmsOutboundMessage {

    private String             messageId;
    private EbxmlMessageHeader header;

    /** Verwijzing naar payload-opslag. */
    private String  payloadRef;
    private String  payloadContentType;

    /** True als de orchestrator het bericht moet laten ondertekenen (osb-*-s/e profielen). */
    @Builder.Default
    private boolean requireSigning = false;

    /** True als de orchestrator het bericht moet laten versleutelen (osb-*-e profielen). */
    @Builder.Default
    private boolean requireEncryption = false;

    private Instant scheduledAt;
}
