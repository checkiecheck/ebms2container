package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/** Request DTO voor XML-Enc encryptie. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncryptRequest {

    @NotBlank(message = "xmlContent mag niet leeg zijn")
    private String xmlContent;

    /** Alias van het ontvanger-certificaat in de KeyStore (voor RSA-OAEP). */
    @NotBlank(message = "recipientKeyAlias mag niet leeg zijn")
    private String recipientKeyAlias;

    private String messageId;
}
