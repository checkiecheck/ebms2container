package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * Response DTO na XML-DSig verificatie.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerifyResponse {

    /** True als de handtekening cryptografisch geldig is. */
    private boolean valid;

    /** Optionele foutdetail bij ongeldige handtekening. */
    private String errorDetail;

    /** Optioneel bericht-ID (uit het request). */
    private String messageId;
}
