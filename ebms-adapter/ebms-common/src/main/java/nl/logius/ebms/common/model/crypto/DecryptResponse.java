package nl.logius.ebms.common.model.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response van de crypto-service na XML-Enc ontsleuteling. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecryptResponse {
    private String decryptedXml;
    private String keyAlias;
    private String messageId;
}
