package nl.logius.ebms.common.model.ebxml;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * ebXML Service element (MessageHeader/Service).
 * Identificeert de abstracte business service conform de CPA.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceType {

    /**
     * Service-naam of URI.
     * Systeemservices (Ping/Echo): {@code urn:oasis:names:tc:ebxml-msg:service}.
     */
    @NotBlank
    private String value;

    /**
     * Optioneel type-attribuut (bijv. {@code urn:oasis:names:tc:ebxml-msg:service-type}).
     */
    private String type;
}
