package nl.logius.ebms.common.model.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

/**
 * AMQP-event gepubliceerd op {@code ebms.ack.events} nadat een uitgaand
 * Reliable Messaging-bericht definitief bevestigd is via een ebMS2 Acknowledgment.
 *
 * <p>Backoffice-systemen kunnen op deze queue luisteren om te weten dat hun
 * bericht succesvol afgeleverd is conform Digikoppeling Koppelvlakstandaard ebMS2.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbmsAckEvent {

    /** MessageId van het originele uitgaande bericht (RefToMessageId uit de ACK). */
    private String messageId;

    private String conversationId;

    /** CPA-identifier waaronder het bericht verstuurd is. */
    private String cpaId;

    /** OIN / PartyId van de partij die de ACK verstuurd heeft (de ontvanger). */
    private String ackSenderPartyId;

    /** Tijdstip waarop de Acknowledgment ontvangen is. */
    @Builder.Default
    private Instant acknowledgedAt = Instant.now();
}
