package nl.logius.ebms.crypto.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.crypto.dto.*;
import nl.logius.ebms.crypto.entity.KeyPairMetadataEntity;
import nl.logius.ebms.crypto.repository.KeyPairMetadataRepository;
import nl.logius.ebms.crypto.service.KeyStoreService;
import nl.logius.ebms.crypto.service.XmlSigningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * REST API voor cryptografische operaties (XML-DSig ondertekening / verificatie).
 *
 * <p>Basepath: {@code /api/crypto}
 *
 * <ul>
 *   <li>POST /api/crypto/sign    – onderteken een XML-document</li>
 *   <li>POST /api/crypto/verify  – verifieer een XML-handtekening</li>
 *   <li>GET  /api/crypto/keys    – lijst beschikbare KeyStore-aliassen</li>
 * </ul>
 *
 * <p>Alleen bereikbaar via intern netwerk (Zero-Trust: geen externe blootstelling).
 */
@RestController
@RequestMapping("/api/crypto")
@RequiredArgsConstructor
@Slf4j
public class CryptoController {

    private final XmlSigningService         xmlSigningService;
    private final KeyStoreService           keyStoreService;
    private final KeyPairMetadataRepository keyMetaRepository;

    // ── XML-DSig Ondertekening ────────────────────────────────────────────

    /**
     * Ondertekent een XML-document (enveloped XML-DSig, RSA-SHA256 of ECDSA-SHA256).
     */
    @PostMapping("/sign")
    public ResponseEntity<SignResponse> sign(@Valid @RequestBody SignRequest request) {
        String signedXml = xmlSigningService.sign(
            request.getXmlContent(),
            request.getKeyAlias(),
            request.getMessageId());

        return ResponseEntity.ok(SignResponse.builder()
            .signedXml(signedXml)
            .keyAlias(request.getKeyAlias())
            .messageId(request.getMessageId())
            .build());
    }

    // ── XML-DSig Verificatie ──────────────────────────────────────────────

    /**
     * Verifieert een XML-DSig handtekening in het aangeleverde XML-document.
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        boolean valid = xmlSigningService.verify(
            request.getSignedXml(),
            request.getMessageId());

        return ResponseEntity.ok(VerifyResponse.builder()
            .valid(valid)
            .messageId(request.getMessageId())
            .errorDetail(valid ? null : "Handtekening is ongeldig of ontbreekt")
            .build());
    }

    // ── KeyStore beheer ────────────────────────────────────────────────────

    /**
     * Geeft een lijst van beschikbare alias-namen in de KeyStore.
     */
    @GetMapping("/keys")
    public ResponseEntity<List<String>> listKeys() {
        try {
            return ResponseEntity.ok(keyStoreService.listAliases());
        } catch (Exception e) {
            log.error("Fout bij ophalen KeyStore-aliassen", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Geeft sleutelmetadata (geen sleutels zelf!) voor een alias.
     */
    @GetMapping("/keys/{alias}")
    public ResponseEntity<KeyPairMetadataEntity> getKeyMetadata(@PathVariable String alias) {
        return keyMetaRepository.findByAlias(alias)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Exception handlers ────────────────────────────────────────────────

    @ExceptionHandler(XmlSecurityException.class)
    public ResponseEntity<ProblemDetail> handleSecurityException(XmlSecurityException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(URI.create("urn:nl:logius:ebms:error:security-failure"));
        pd.setTitle("XML-beveiligingsfout");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        log.error("Onverwachte fout in crypto-service", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(URI.create("urn:nl:logius:ebms:error:internal"));
        pd.setTitle("Interne fout");
        pd.setDetail("Interne verwerkingsfout. Raadpleeg de logs.");
        return ResponseEntity.internalServerError().body(pd);
    }
}
