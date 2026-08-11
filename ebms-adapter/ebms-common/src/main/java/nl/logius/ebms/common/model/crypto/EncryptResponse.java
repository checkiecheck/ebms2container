package nl.logius.ebms.common.model.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response van de crypto-service na XML-Enc versleuteling. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncryptResponse {
    private String encryptedXml;
    private String recipientKeyAlias;
    private String messageId;
}
