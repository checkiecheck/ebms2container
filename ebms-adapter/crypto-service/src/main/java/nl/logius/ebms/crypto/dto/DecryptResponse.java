package nl.logius.ebms.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Response DTO na succesvolle XML-Enc decryptie. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecryptResponse {

    /** Ontsleuteld XML-document (UTF-8). */
    private String decryptedXml;
    private String keyAlias;
    private String messageId;
}
