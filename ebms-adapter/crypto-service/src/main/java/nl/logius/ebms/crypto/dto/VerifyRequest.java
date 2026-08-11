package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Request DTO voor XML-DSig handtekeningverificatie.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerifyRequest {

    /** Het ondertekende XML-document (UTF-8 string). */
    @NotBlank(message = "signedXml mag niet leeg zijn")
    private String signedXml;

    /** Optioneel bericht-ID voor audit-logging. */
    private String messageId;
}
