package nl.logius.ebms.common.model.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;

import java.time.Instant;

/**
 * AMQP-bericht voor binnenkomende ebMS2-berichten.
 * Gepubliceerd door {@code ebms-orchestrator} op queue {@code ebms.inbound.messages}
 * na succesvolle SOAP-ontvangst en state-persistentie.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbmsInboundMessage {

    /** Intern database-ID van het bericht. */
    private String messageId;

    /** ebXML ConversationId voor correlatie. */
    private String conversationId;

    /** Volledig geparsed MessageHeader object. */
    private EbxmlMessageHeader header;

    /** Verwijzing naar payload-opslag (bijv. S3-sleutel of bestandspad). */
    private String payloadRef;

    /** MIME-type van de payload (bijv. {@code application/xml}). */
    private String payloadContentType;

    /** Ruw SOAP-bericht (alleen voor debugging; weglaten in productie). */
    private String rawSoapXml;

    private Instant receivedAt;

    /** True als de handtekening succesvol geverifieerd is door crypto-service. */
    @Builder.Default
    private boolean signatureVerified = false;

    /** True als het bericht versleuteld was (XML-Enc). */
    @Builder.Default
    private boolean encryptionApplied = false;
}
