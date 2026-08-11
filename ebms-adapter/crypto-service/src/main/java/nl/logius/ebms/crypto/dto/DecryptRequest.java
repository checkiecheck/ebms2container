package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/** Request DTO voor XML-Enc decryptie. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecryptRequest {

    @NotBlank(message = "encryptedXml mag niet leeg zijn")
    private String encryptedXml;

    /** Alias van onze private sleutel in de KeyStore (voor RSA-OAEP ontsleuteling). */
    @NotBlank(message = "keyAlias mag niet leeg zijn")
    private String keyAlias;

    private String messageId;
}
