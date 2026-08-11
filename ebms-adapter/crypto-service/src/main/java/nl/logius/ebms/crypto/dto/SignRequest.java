package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Request DTO voor XML-DSig ondertekening.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignRequest {

    /** Te ondertekenen XML-document (UTF-8 string). */
    @NotBlank(message = "xmlContent mag niet leeg zijn")
    private String xmlContent;

    /** Alias van het sleutelpaar in de KeyStore. */
    @NotBlank(message = "keyAlias mag niet leeg zijn")
    private String keyAlias;

    /** Optioneel bericht-ID voor audit-logging. */
    private String messageId;
}
