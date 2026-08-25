package nl.logius.ebms.common.model.cpa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

/**
 * Partnercertificaat (X.509/PEM) per CPA-partij, zoals teruggegeven door
 * {@code cpa-service}'s {@code GET /api/cpa/{cpaId}/certificates/{partyId}}.
 *
 * <p>Gebruikt door de orchestrator om de outbound mTLS trust dynamisch op te bouwen
 * (i.p.v. een statische truststore) via {@link nl.logius.ebms.orchestrator.soap.OutboundSoapClient}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartnerCertificateDto {

    private String id;
    private String cpaId;
    private String partyId;
    private String certificateAlias;

    /** X.509-certificaat in PEM-formaat (-----BEGIN CERTIFICATE----- ...). */
    private String certificatePem;

    private Instant validFrom;
    private Instant validUntil;

    /** Gebruik: SIGNING | ENCRYPTION | SIGNING_ENCRYPTION */
    private String certificateUsage;
    private Instant createdAt;
}
