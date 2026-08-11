package nl.logius.ebms.common.model.ebxml;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * ebXML PartyId element (MessageHeader/From/PartyId of To/PartyId).
 * Bevat een waarde en een optioneel type-URI (bijv. OIN-type).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartyId {

    /** Partij-identifier (bijv. OIN-waarde: 00000000000000000000). */
    @NotBlank
    private String value;

    /**
     * Type-URI conform ebXML CPPA 2.0.
     * Digikoppeling gebruikt: {@code urn:oasis:names:tc:ebxml-cppa:partyid-type:HIN}
     */
    private String type;
}
