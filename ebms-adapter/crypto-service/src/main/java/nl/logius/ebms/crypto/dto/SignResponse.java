package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * Response DTO na succesvolle XML-DSig ondertekening.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignResponse {

    /** Het ondertekende XML-document (UTF-8 string). */
    private String signedXml;

    /** Alias van het gebruikte sleutelpaar. */
    private String keyAlias;

    /** Gebruikte handtekening-algoritme (bijv. RSA-SHA256). */
    private String algorithm;

    /** Optioneel bericht-ID (uit het request). */
    private String messageId;
}
