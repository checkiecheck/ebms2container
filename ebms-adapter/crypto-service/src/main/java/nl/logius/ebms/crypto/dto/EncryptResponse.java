package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response DTO na succesvolle XML-Enc encryptie. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncryptResponse {

    /** Versleuteld XML-document (UTF-8) met embedded EncryptedKey. */
    private String encryptedXml;
    private String recipientKeyAlias;
    private String messageId;
}
