package nl.logius.ebms.common.model.amqp;

import lombok.*;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;

/** Durable background task for an asynchronous ebMS Ping/Pong response. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EbmsAsyncPongMessage {

    private EbxmlMessageHeader pingHeader;
}
