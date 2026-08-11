package nl.logius.ebms.common.model.cpa;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * Partij-informatie geëxtraheerd uit de CPA (PartyInfo element).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartyInfoDto {

    private String  id;
    private String  cpaId;
    private String  partyId;
    private String  partyIdType;

    /** Organisatie Identificatie Nummer (ISO 6523, 20 cijfers). */
    private String  oin;

    @Builder.Default
    private boolean oinValidated = false;

    private String  role;
    private String  service;
}
