package nl.logius.ebms.common.model.ebxml;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

/**
 * ebXML MessageInfo element.
 * Bevat MessageId, Timestamp en optioneel RefToMessageId (voor ACK's en antwoorden).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageInfo {

    /** Tijdstip van aanmaak conform ISO 8601 / UTC. */
    @NotNull
    private Instant timestamp;

    /**
     * Globaal uniek bericht-ID (bijv. UUID@hostname).
     * Gebruikt voor duplicate suppression.
     */
    @NotBlank
    private String messageId;

    /**
     * Verwijst naar het MessageId van het voorgaande bericht.
     * Verplicht voor ACK's, Errors en Pong-berichten; null voor initiërende berichten.
     */
    private String refToMessageId;
}
