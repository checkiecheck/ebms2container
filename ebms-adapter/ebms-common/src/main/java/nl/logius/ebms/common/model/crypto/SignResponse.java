package nl.logius.ebms.common.model.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response van de crypto-service na XML-DSig signing. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignResponse {
    private String signedXml;
    private String keyAlias;
    private String algorithm;
    private String messageId;
}
