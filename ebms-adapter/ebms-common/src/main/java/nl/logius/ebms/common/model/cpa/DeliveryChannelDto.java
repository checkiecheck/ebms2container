package nl.logius.ebms.common.model.cpa;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * Technisch afleverkanaal per CPA-partij conform ebXML CPPA 2.0 (DeliveryChannel element).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryChannelDto {

    private String id;
    private String cpaId;
    private String partyId;
    private String channelId;

    /**
     * Digikoppeling-profiel code:
     * osb-be | osb-rm | osb-be-s | osb-rm-s | osb-be-e | osb-rm-e
     */
    private String dkProfile;

    @Builder.Default
    private String transportProtocol = "HTTP";
    private String endpointUrl;

    /** Maximaal aantal retries (alleen bij rm-profielen). */
    private Integer retryCount;

    /** Interval tussen retries in seconden. */
    private Integer retryInterval;

    /** MessageExpiry in seconden (persistDuration). */
    private Integer persistDuration;
}
