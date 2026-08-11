package nl.logius.ebms.cpa.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import nl.logius.ebms.cpa.service.CpaService;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API voor CPA-beheer en -opzoekingen.
 *
 * <p>Basepath: {@code /api/cpa}
 *
 * <ul>
 *   <li>GET  /api/cpa                      – alle CPA's
 *   <li>GET  /api/cpa/{cpaId}              – één CPA op ID (gecached)
 *   <li>POST /api/cpa                      – nieuwe CPA aanmaken
 *   <li>DELETE /api/cpa/{cpaId}            – CPA verwijderen
 *   <li>GET  /api/cpa/{cpaId}/parties      – partijen per CPA
 *   <li>GET  /api/cpa/parties/oin/{oin}    – partijen per OIN
 *   <li>POST /api/cpa/{cpaId}/certificates – certificaat toevoegen
 * </ul>
 */
@RestController
@RequestMapping("/api/cpa")
@RequiredArgsConstructor
public class CpaController {

    private final CpaService cpaService;

    // ── CPA CRUD ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<CpaDto>> findAll() {
        return ResponseEntity.ok(cpaService.findAll());
    }

    @GetMapping("/{cpaId}")
    public ResponseEntity<CpaDto> findByCpaId(@PathVariable String cpaId) {
        return ResponseEntity.ok(cpaService.findByCpaId(cpaId));
    }

    @PostMapping
    public ResponseEntity<CpaDto> create(@Valid @RequestBody CpaDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cpaService.create(dto));
    }

    @DeleteMapping("/{cpaId}")
    public ResponseEntity<Void> delete(@PathVariable String cpaId) {
        cpaService.deleteByCpaId(cpaId);
        return ResponseEntity.noContent().build();
    }

    // ── Partijen ──────────────────────────────────────────────────────────

    @GetMapping("/{cpaId}/parties")
    public ResponseEntity<List<PartyInfoDto>> findParties(@PathVariable String cpaId) {
        return ResponseEntity.ok(cpaService.findPartiesByCpaId(cpaId));
    }

    @GetMapping("/parties/oin/{oin}")
    public ResponseEntity<List<PartyInfoDto>> findByOin(@PathVariable String oin) {
        return ResponseEntity.ok(cpaService.findPartiesByOin(oin));
    }

    // ── Afleverkanalen ────────────────────────────────────────────────────

    /**
     * Alle afleverkanalen voor een specifieke CPA.
     * Gebruikt door de orchestrator om endpoint-URL's en DK-profielen op te halen.
     */
    @GetMapping("/{cpaId}/channels")
    public ResponseEntity<List<DeliveryChannelDto>> findChannels(@PathVariable String cpaId) {
        return ResponseEntity.ok(cpaService.findDeliveryChannels(cpaId));
    }

    /**
     * Afleverkanaal voor een specifieke CPA en partij-ID.
     * Primair endpoint voor de orchestrator vóór het versturen van een bericht.
     */
    @GetMapping("/{cpaId}/channels/{partyId}")
    public ResponseEntity<DeliveryChannelDto> findChannel(
            @PathVariable String cpaId,
            @PathVariable String partyId) {
        return ResponseEntity.ok(cpaService.findDeliveryChannel(cpaId, partyId));
    }

    /**
     * Voegt een afleverkanaal toe aan een bestaande CPA.
     */
    @PostMapping("/{cpaId}/channels")
    public ResponseEntity<DeliveryChannelDto> addChannel(
            @PathVariable String cpaId,
            @Valid @RequestBody DeliveryChannelDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(cpaService.addDeliveryChannel(cpaId, dto));
    }

    // ── Certificaten ──────────────────────────────────────────────────────

    @PostMapping("/{cpaId}/certificates")
    public ResponseEntity<PartnerCertificateEntity> addCertificate(
            @PathVariable String cpaId,
            @RequestBody PartnerCertificateEntity cert) {
        cert = PartnerCertificateEntity.builder()
            .cpaId(cpaId)
            .partyId(cert.getPartyId())
            .certificateAlias(cert.getCertificateAlias())
            .certificatePem(cert.getCertificatePem())
            .validFrom(cert.getValidFrom())
            .validUntil(cert.getValidUntil())
            .certificateUsage(cert.getCertificateUsage())
            .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(cpaService.addCertificate(cert));
    }

    @GetMapping("/{cpaId}/certificates/{partyId}")
    public ResponseEntity<List<PartnerCertificateEntity>> getCertificates(
            @PathVariable String cpaId,
            @PathVariable String partyId) {
        return ResponseEntity.ok(cpaService.findValidCertificates(cpaId, partyId));
    }
}
