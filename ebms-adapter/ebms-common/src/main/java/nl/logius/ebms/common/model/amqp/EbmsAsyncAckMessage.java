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
    private String fromPartyId;

    @Builder.Default
    private boolean ackRequestedSigned = false;
}
