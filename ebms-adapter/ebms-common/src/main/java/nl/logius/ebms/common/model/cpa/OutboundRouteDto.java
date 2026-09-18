package nl.logius.ebms.common.model.cpa;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutboundRouteDto {

    private String cpaId;
    private String fromPartyId;
    private String toPartyId;
    private String service;
    private String serviceType;
    private String action;
    private String fromRole;
    private String toRole;
    private boolean signatureRequired;
    private String hashFunction;
    private String signatureAlgorithm;
    private DeliveryChannelDto channel;
}