package nl.logius.ebms.common.model.ebxml;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * ebXML AckRequested element.
 * Aanwezig in de SOAP-header wanneer de verzender een ACK vereist.
 * Van toepassing bij rm-profielen (osb-rm, osb-rm-s, osb-rm-e).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AckRequested {

    /** True als de ACK zelf ook ondertekend moet zijn. */
    @Builder.Default
    private boolean signed = false;

    /**
     * SOAP actor/role die de ACK moet verwerken.
     * Typisch: {@code urn:oasis:names:tc:ebxml-msg:actor:toPartyMSH}.
     */
    private String actor;

    /** True = mustUnderstand="1" in de SOAP-header. */
    @Builder.Default
    private boolean mustUnderstand = true;
}
