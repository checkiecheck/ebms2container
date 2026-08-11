package nl.logius.ebms.orchestrator.service;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.common.model.crypto.DecryptResponse;
import nl.logius.ebms.common.model.crypto.EncryptResponse;
import nl.logius.ebms.common.model.crypto.SignResponse;
import nl.logius.ebms.common.model.crypto.VerifyResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Zero-Trust facade naar de afgeschermde {@code crypto-service} (:8082).
 *
 * <p>Strict Privileged Separation (BIO / Logius): de orchestrator heeft
 * <strong>nooit</strong> directe toegang tot keystores of private keys.
 * Alle cryptografische bewerkingen verlopen uitsluitend via dit doorgeefluik.
 *
 * <p>HTTP 4xx / 5xx responses van de crypto-service worden vertaald naar
 * {@link XmlSecurityException}, zodat het type fout uniform afgehandeld wordt.
 */
@Service
@Slf4j
public class CryptoServiceClient {

    private final RestClient cryptoRestClient;

    public CryptoServiceClient(@Qualifier("cryptoRestClient") RestClient cryptoRestClient) {
        this.cryptoRestClient = cryptoRestClient;
    }

    // ── XML-DSig ─────────────────────────────────────────────────────────────

    /**
     * Ondertekent een XML-document met de private key van het opgegeven alias
     * (RSA-SHA256 of ECDSA-SHA256, conform Digikoppeling PKIoverheid-vereisten).
     *
     * @param xmlContent  het te ondertekenen XML-document (UTF-8)
     * @param keyAlias    alias van de signing-key in de keystore van de crypto-service
     * @param messageId   ebXML MessageId (gebruikt voor audit-logging)
     * @return ondertekend XML-document als string
     * @throws XmlSecurityException bij fout in de crypto-service
     */
    public String sign(String xmlContent, String keyAlias, String messageId) {
        log.debug("[CRYPTO] Ondertekenen: messageId={} keyAlias={}", messageId, keyAlias);
        try {
            ResponseEntity<SignResponse> response = cryptoRestClient.post()
                .uri("/api/crypto/sign")
                .body(Map.of(
                    "xmlContent", xmlContent,
                    "keyAlias",   keyAlias,
                    "messageId",  messageId))
                .retrieve()
                .toEntity(SignResponse.class);

            if (response.getBody() == null || response.getBody().getSignedXml() == null) {
                throw new XmlSecurityException("Lege signing-respons ontvangen van crypto-service");
            }
            log.debug("[CRYPTO] Ondertekening geslaagd: messageId={}", messageId);
            return response.getBody().getSignedXml();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("[CRYPTO] Signing mislukt: messageId={} status={}", messageId, e.getStatusCode());
            throw new XmlSecurityException(
                "XML-DSig signing mislukt (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (XmlSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CRYPTO] Signing onbereikbaar: messageId={}", messageId, e);
            throw new XmlSecurityException("crypto-service onbereikbaar: " + e.getMessage());
        }
    }

    /**
     * Verifieert de XML-DSig handtekening van een inkomend bericht.
     *
     * @param signedXml het ondertekend XML-document
     * @param messageId ebXML MessageId
     * @return {@code true} als de handtekening geldig is
     * @throws XmlSecurityException bij een ongeldige handtekening of servicefout
     */
    public boolean verify(String signedXml, String messageId) {
        log.debug("[CRYPTO] Verificatie: messageId={}", messageId);
        try {
            ResponseEntity<VerifyResponse> response = cryptoRestClient.post()
                .uri("/api/crypto/verify")
                .body(Map.of(
                    "signedXml", signedXml,
                    "messageId", messageId))
                .retrieve()
                .toEntity(VerifyResponse.class);

            if (response.getBody() == null) {
                throw new XmlSecurityException("Lege verify-respons ontvangen van crypto-service");
            }
            boolean valid = Boolean.TRUE.equals(response.getBody().isValid());
            log.debug("[CRYPTO] Verificatie resultaat: messageId={} valid={}", messageId, valid);

            if (!valid) {
                throw new XmlSecurityException(
                    "XML-DSig handtekening ongeldig voor messageId=" + messageId);
            }
            return true;

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("[CRYPTO] Verificatie mislukt: messageId={} status={}", messageId, e.getStatusCode());
            throw new XmlSecurityException(
                "XML-DSig verificatie mislukt (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (XmlSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CRYPTO] Verificatie onbereikbaar: messageId={}", messageId, e);
            throw new XmlSecurityException("crypto-service onbereikbaar: " + e.getMessage());
        }
    }

    // ── XML-Enc ───────────────────────────────────────────────────────────────

    /**
     * Versleutelt een XML-document met AES-256-GCM (sessiesleutel ingepakt via RSA-OAEP).
     *
     * @param xmlContent     het te versleutelen XML-document
     * @param recipientAlias alias van het ontvangerscertificaat in de keystore
     * @param messageId      ebXML MessageId
     * @return versleuteld XML-document als string
     * @throws XmlSecurityException bij fout in de crypto-service
     */
    public String encrypt(String xmlContent, String recipientAlias, String messageId) {
        log.debug("[CRYPTO] Versleutelen: messageId={} recipientAlias={}", messageId, recipientAlias);
        try {
            ResponseEntity<EncryptResponse> response = cryptoRestClient.post()
                .uri("/api/crypto/encrypt")
                .body(Map.of(
                    "xmlContent",       xmlContent,
                    "recipientKeyAlias", recipientAlias,
                    "messageId",        messageId))
                .retrieve()
                .toEntity(EncryptResponse.class);

            if (response.getBody() == null || response.getBody().getEncryptedXml() == null) {
                throw new XmlSecurityException("Lege encrypt-respons ontvangen van crypto-service");
            }
            log.debug("[CRYPTO] Versleuteling geslaagd: messageId={}", messageId);
            return response.getBody().getEncryptedXml();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("[CRYPTO] Versleuteling mislukt: messageId={} status={}", messageId, e.getStatusCode());
            throw new XmlSecurityException(
                "XML-Enc versleuteling mislukt (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (XmlSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CRYPTO] Versleuteling onbereikbaar: messageId={}", messageId, e);
            throw new XmlSecurityException("crypto-service onbereikbaar: " + e.getMessage());
        }
    }

    /**
     * Ontsleutelt een XML-Enc bericht met de private key van het opgegeven alias.
     *
     * @param encryptedXml het versleuteld XML-document
     * @param keyAlias     alias van de decryptie-key in de keystore
     * @param messageId    ebXML MessageId
     * @return ontsleuteld XML-document als string
     * @throws XmlSecurityException bij fout in de crypto-service
     */
    public String decrypt(String encryptedXml, String keyAlias, String messageId) {
        log.debug("[CRYPTO] Ontsleutelen: messageId={} keyAlias={}", messageId, keyAlias);
        try {
            ResponseEntity<DecryptResponse> response = cryptoRestClient.post()
                .uri("/api/crypto/decrypt")
                .body(Map.of(
                    "encryptedXml", encryptedXml,
                    "keyAlias",     keyAlias,
                    "messageId",    messageId))
                .retrieve()
                .toEntity(DecryptResponse.class);

            if (response.getBody() == null || response.getBody().getDecryptedXml() == null) {
                throw new XmlSecurityException("Lege decrypt-respons ontvangen van crypto-service");
            }
            log.debug("[CRYPTO] Ontsleuteling geslaagd: messageId={}", messageId);
            return response.getBody().getDecryptedXml();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("[CRYPTO] Ontsleuteling mislukt: messageId={} status={}", messageId, e.getStatusCode());
            throw new XmlSecurityException(
                "XML-Enc ontsleuteling mislukt (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (XmlSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CRYPTO] Ontsleuteling onbereikbaar: messageId={}", messageId, e);
            throw new XmlSecurityException("crypto-service onbereikbaar: " + e.getMessage());
        }
    }
}
