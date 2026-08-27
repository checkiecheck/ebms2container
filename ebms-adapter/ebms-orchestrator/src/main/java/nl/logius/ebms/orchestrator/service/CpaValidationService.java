package nl.logius.ebms.orchestrator.service;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Valideert een binnenkomend ebMS2-bericht tegen de CPA-registry en de OIN-autorisatie.
 *
 * <p>Stappen:
 * <ol>
 *   <li>Verifieer dat de CPA bestaat en de status {@code ACTIVE} heeft</li>
 *   <li>Verifieer dat het OIN (uit mTLS-header) geautoriseerd is voor de CPA</li>
 * </ol>
 *
 * <p>Bij onbereikbaarheid van de cpa-service wordt een
 * {@link CpaValidationResult#serviceUnavailable(String)} teruggegeven; de orchestrator
 * bepaalt zelf of dit een fail-open of fail-closed scenario is.
 */
@Service
@Slf4j
public class CpaValidationService {

    private final RestClient cpaRestClient;

    public CpaValidationService(@Qualifier("cpaRestClient") RestClient cpaRestClient) {
        this.cpaRestClient = cpaRestClient;
    }

    /**
     * Valideert CPA-ID en optioneel OIN voor een binnenkomend bericht.
     *
     * @param cpaId     de CPA-identifier uit de ebXML MessageHeader
     * @param clientOin het OIN uit de X-Forwarded-Client-OIN mTLS-header (mag null zijn)
     * @return {@link CpaValidationResult} met validatiestatus
     */
    public CpaValidationResult validateCpaAndOin(String cpaId, String clientOin) {

        // ── Stap 1: CPA ophalen en statuscheck ────────────────────────────
        CpaDto cpa;
        try {
            cpa = cpaRestClient.get()
                .uri("/api/cpa/{cpaId}", cpaId)
                .retrieve()
                .body(CpaDto.class);

            if (cpa == null) {
                log.warn("[CPA-VALIDATION] Lege respons voor cpaId={}", cpaId);
                return CpaValidationResult.failure("CPA niet gevonden: " + cpaId);
            }
            if (!"ACTIVE".equalsIgnoreCase(cpa.getStatus())) {
                log.warn("[CPA-VALIDATION] CPA niet actief: {} status={}", cpaId, cpa.getStatus());
                return CpaValidationResult.failure(
                    "CPA is niet actief (status=" + cpa.getStatus() + "): " + cpaId);
            }
            log.debug("[CPA-VALIDATION] CPA gevonden en actief: {}", cpaId);

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("[CPA-VALIDATION] CPA niet gevonden: {}", cpaId);
            return CpaValidationResult.failure("CPA niet gevonden: " + cpaId);
        } catch (Exception e) {
            log.warn("[CPA-VALIDATION] cpa-service onbereikbaar ({}): {}", cpaId, e.getMessage());
            return CpaValidationResult.serviceUnavailable(e.getMessage());
        }

        // ── Stap 2: OIN-autorisatiecheck ──────────────────────────────────
        if (clientOin != null && !clientOin.isBlank()) {
            try {
                List<PartyInfoDto> parties = cpaRestClient.get()
                    .uri("/api/cpa/parties/oin/{oin}", clientOin)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

                boolean oinMatchesCpa = parties != null && parties.stream()
                    .anyMatch(p -> cpaId.equals(p.getCpaId()));

                if (!oinMatchesCpa) {
                    log.warn("[CPA-VALIDATION] OIN {} niet geautoriseerd voor CPA {}", clientOin, cpaId);
                    return CpaValidationResult.failure(
                        "OIN " + clientOin + " is niet geautoriseerd voor CPA: " + cpaId);
                }
                log.debug("[CPA-VALIDATION] OIN {} geautoriseerd voor CPA {}", clientOin, cpaId);

            } catch (Exception e) {
                // Fail-open voor OIN: log maar blokkeer niet (vermijd lock-out bij cpa-serviceprobleem)
                log.warn("[CPA-VALIDATION] OIN-lookup gefaald voor {} (fail-open): {}", clientOin, e.getMessage());
            }
        }

        return CpaValidationResult.success(cpa);
    }

    /**
     * Haalt het afleverkanaal op voor een specifieke CPA en ontvangende partij.
     * Gebruikt door de orchestrator vóór het versturen van een uitgaand bericht.
     *
     * @param cpaId     de CPA-identifier
     * @param toPartyId partij-ID van de ontvanger
     * @return {@link DeliveryChannelDto} met endpoint-URL, DK-profiel en RM-parameters
     * @throws EbmsException als het kanaal niet gevonden wordt of de service onbereikbaar is
     */
public DeliveryChannelDto getDeliveryChannel(String cpaId, String toPartyId) {
    log.debug("[CPA] Afleverkanaal opzoeken: cpaId={} toPartyId={}", cpaId, toPartyId);
    try {
        DeliveryChannelDto channel = cpaRestClient.get()
            .uri("/api/cpa/{cpaId}/channels/{partyId}", cpaId, toPartyId)
            .retrieve()
            .body(DeliveryChannelDto.class);

        if (channel == null || channel.getEndpointUrl() == null) {
            throw new EbmsException("CHANNEL_NOT_FOUND",
                "Geen geldig afleverkanaal voor CPA=" + cpaId + " party=" + toPartyId);
        }
        log.debug("[CPA] Afleverkanaal gevonden: {}", channel.getEndpointUrl());
        return channel;

    } catch (HttpClientErrorException.NotFound e) {
        // Vangt de formele 404 op (als de CPA zelf of het endpoint onvindbaar is)
        throw new EbmsException("CHANNEL_NOT_FOUND",
            "Afleverkanaal niet gevonden (404): CPA=" + cpaId + " party=" + toPartyId);
            
    } catch (HttpClientErrorException.BadRequest e) {
        // FIX: Dit vangt de HTTP 400 op die de cpa-service teruggeeft bij CHANNEL_NOT_FOUND!
        throw new EbmsException("CHANNEL_NOT_FOUND",
            "Afleverkanaal niet geconfigureerd in CPA (400): CPA=" + cpaId + " party=" + toPartyId);
            
    } catch (HttpClientErrorException e) {
        // Vangt eventuele andere 4xx fouten op (zoals 401 of 403)
        throw new EbmsException("CHANNEL_NOT_FOUND",
            "Client-fout bij CPA-lookup (" + e.getStatusCode() + "): " + e.getMessage());
            
    } catch (EbmsException e) {
        throw e;
        
    } catch (Exception e) {
        // Dit blok wordt nu ALLEEN nog maar bereikt als de cpa-service écht offline is (bijv. netwerkfout of 500 error)
        log.error("[CPA] cpa-service onbereikbaar bij kanaal-lookup: {}", e.getMessage());
        throw new EbmsException("CPA_SERVICE_UNAVAILABLE",
            "cpa-service onbereikbaar voor kanaal-lookup: " + e.getMessage());
    }
}

    /**
     * Haalt de geldige (niet-verlopen) partnercertificaten op voor een CPA-partij.
     *
     * <p>Gebruikt door {@link nl.logius.ebms.orchestrator.soap.OutboundSoapClient} om de
     * outbound mTLS trust dynamisch op te bouwen op basis van de CPA-registry, i.p.v. een
     * statische truststore. Fail-closed: zonder geldig certificaat wordt het bericht direct
     * als mislukt beschouwd.
     *
     * @param cpaId  de CPA-identifier
     * @param partyId partij-ID van de ontvanger
     * @return niet-lege lijst van geldige {@link PartnerCertificateDto}'s
     * @throws EbmsException als er geen geldig certificaat gevonden wordt of de service onbereikbaar is
     */
    public List<PartnerCertificateDto> getPartnerCertificates(String cpaId, String partyId) {
        log.debug("[CPA] Partnercertificaten opzoeken: cpaId={} partyId={}", cpaId, partyId);
        try {
            List<PartnerCertificateDto> certificates = cpaRestClient.get()
                .uri("/api/cpa/{cpaId}/certificates/{partyId}", cpaId, partyId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

            if (certificates == null || certificates.isEmpty()) {
                throw new EbmsException("CERTIFICATE_NOT_FOUND",
                    "Geen geldig partnercertificaat gevonden voor CPA=" + cpaId + " party=" + partyId);
            }
            log.debug("[CPA] {} geldig(e) partnercertificaat(en) gevonden: CPA={} party={}",
                certificates.size(), cpaId, partyId);
            return certificates;

        } catch (HttpClientErrorException.NotFound e) {
            throw new EbmsException("CERTIFICATE_NOT_FOUND",
                "Geen partnercertificaat gevonden: CPA=" + cpaId + " party=" + partyId);
        } catch (EbmsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CPA] cpa-service onbereikbaar bij certificaat-lookup: {}", e.getMessage());
            throw new EbmsException("CPA_SERVICE_UNAVAILABLE",
                "cpa-service onbereikbaar voor certificaat-lookup: " + e.getMessage());
        }
    }
}
