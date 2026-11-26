package nl.logius.ebms.common.model.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Durable background task for an asynchronous ebMS2 Acknowledgment. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbmsAsyncAckMessage {

    private String messageId;
    private String cpaId;
    /** Partij van de oorspronkelijke verzender (ontvanger van de ACK). */
    private String fromPartyId;

    /** Eigen partij (verzender van de ACK); bepaalt het mTLS-clientcertificaat. Kan ontbreken bij oudere taken. */
    private String toPartyId;

    @Builder.Default
    private boolean ackRequestedSigned = false;
}
