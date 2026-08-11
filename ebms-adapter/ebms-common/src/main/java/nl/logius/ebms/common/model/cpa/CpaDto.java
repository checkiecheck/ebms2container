package nl.logius.ebms.common.model.cpa;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * Data Transfer Object voor een Collaboration Protocol Agreement (CPA).
 * Conform OASIS ebXML CPPA v2.0.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CpaDto {

    /** Interne database-UUID (alleen aanwezig in responses). */
    private String id;

    /** Unieke CPA-identifier zoals gedefinieerd in het CPA-document. */
    @NotBlank
    private String cpaId;

    private String  version;
    private String  description;
    private Instant startDate;
    private Instant endDate;

    /** ACTIVE | DEPRECATED | REVOKED */
    @Builder.Default
    private String status = "ACTIVE";

    /** Volledig CPA-XML-document (base64 of plain XML string). */
    @NotBlank
    private String cpaXml;

    private Instant createdAt;
    private Instant updatedAt;

    private List<PartyInfoDto>      parties;
    private List<DeliveryChannelDto> channels;
}
