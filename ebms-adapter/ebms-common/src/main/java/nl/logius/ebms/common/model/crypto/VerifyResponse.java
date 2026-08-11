package nl.logius.ebms.common.model.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response van de crypto-service na XML-DSig verificatie. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerifyResponse {
    private boolean valid;
    private String  messageId;
    private String  signerCn;
}
