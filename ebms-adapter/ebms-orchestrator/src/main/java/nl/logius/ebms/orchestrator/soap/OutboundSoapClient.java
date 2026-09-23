package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.soap.SOAPMessage;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * HTTP-client voor het versturen van ondertekende en/of versleutelde ebMS2-enveloppen
 * (SOAP 1.1, Message mode).
 *
 * <p>Design-keuzes:
 * <ul>
    *   <li>Geen WSDL vereist: raw SOAP over HTTP, zodat HTTP 204 No Content kan worden
    *       afgehandeld voordat een SOAP-parser de lege body probeert te lezen.</li>
 *   <li>Timeouts configureerbaar via {@code application.yml} (BIO-vereiste: voorkomen van
 *       thread-exhaustion bij trage overheidsvoorzieningen).</li>
 *   <li>mTLS: bij HTTPS-endpoints wordt de trust dynamisch opgebouwd op basis van het
 *       partnercertificaat uit de CPA-registry ({@link CpaValidationService}) i.p.v. een
 *       statische truststore. Zonder geldig CPA-certificaat wordt het bericht direct als
 *       mislukt beschouwd (fail-closed) – zie {@link #buildDynamicSslContext}.</li>
 * </ul>
 */
@Component
@Slf4j
public class OutboundSoapClient {

    private static final String EBMS_SOAP_ACTION = "ebXML";

    @Value("${ebms.outbound.connect-timeout-ms:10000}")
    private long connectTimeoutMs;

    @Value("${ebms.outbound.read-timeout-ms:30000}")
    private long readTimeoutMs;

    private final EbmsOutboundSSLProperties sslProperties;
    private final CpaValidationService cpaValidationService;
    private final SoapHelper soapHelper;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_READ_TIMEOUT_MS = 30_000L;

    public OutboundSoapClient(EbmsOutboundSSLProperties sslProperties,
                               CpaValidationService cpaValidationService,
                               SoapHelper soapHelper) {
        this.sslProperties = sslProperties;
        this.cpaValidationService = cpaValidationService;
        this.soapHelper = soapHelper;
    }

    /**
     * Verstuurt een (gesigneerd en/of versleuteld) SOAP-bericht naar het opgegeven endpoint.
     *
            SOAPMessage soapMessage = MessageFactory.newInstance().createMessage(
     * wordt de partner-mTLS-trust real-time opgebouwd via de CPA-registry.
     * Bij een SOAP Fault, ontbrekend partnercertificaat of verbindingsfout wordt een
     * {@link EbmsException} gegooid.
     *
     * @param endpointUrl het HTTPS-endpoint van de ketenpartner (uit CPA)
     * @param rawSoapXml  het volledig geserialiseerde SOAP-bericht als UTF-8 string
     * @param cpaId       de CPA-identifier van het afleverkanaal (voor dynamische mTLS-trust)
     * @param toPartyId   partij-ID van de ontvanger (voor dynamische mTLS-trust)
     * @return het SOAP-antwoord van de partner (ACK, Pong of Error)
     * @throws EbmsException bij verbindingsfout, ontbrekend partnercertificaat of SOAP Fault
     */
    public SOAPMessage send(String endpointUrl, String rawSoapXml, String cpaId, String toPartyId) {
        log.info("[OUTBOUND] Verzenden naar endpoint={} cpaId={} toPartyId={}",
            endpointUrl, cpaId, toPartyId);
        try {
            // ── 1. SOAPMessage reconstrueren uit string ────────────────────
            MessageFactory.newInstance().createMessage(
                new MimeHeaders(),
                new ByteArrayInputStream(rawSoapXml.getBytes(StandardCharsets.UTF_8)));

            // ── 2. Bericht verzenden ──────────────────────────────────────
            SOAPMessage response = invoke(endpointUrl, rawSoapXml, cpaId, toPartyId);

            // ── 6. SOAP Fault check ───────────────────────────────────────
            if (response != null && response.getSOAPBody() != null
                    && response.getSOAPBody().hasFault()) {
                String faultString = response.getSOAPBody().getFault().getFaultString();
                log.error("[OUTBOUND] SOAP Fault ontvangen van {}: {}", endpointUrl, faultString);
                throw new EbmsException("SOAP_FAULT",
                    "SOAP Fault van partner endpoint (" + endpointUrl + "): " + faultString);
            }

            // ── 6b. ebXML ErrorList check (partnerafwijzing zonder native SOAP Fault) ──
            // Een ebMS2-afwijzing (bv. SecurityFailure, ValueNotRecognized) zit als eb:ErrorList
            // in de SOAP-header, niet als SOAP-Fault in de body - zonder deze check werd zo'n
            // afwijzing als succesvolle aflevering geboekt (zie createErrorResponse()).
            SoapHelper.EbxmlError ebxmlError = response != null ? soapHelper.parseErrorList(response) : null;
            if (ebxmlError != null) {
                log.error("[OUTBOUND] ebXML ErrorList ontvangen van {}: errorCode={} beschrijving={}",
                    endpointUrl, ebxmlError.errorCode(), ebxmlError.description());
                throw new EbmsException("PARTNER_REJECTED",
                    "ebXML ErrorList van partner endpoint (" + endpointUrl + "): ["
                        + ebxmlError.errorCode() + "] " + ebxmlError.description());
            }

            log.info("[OUTBOUND] Verzending geslaagd naar endpoint={}", endpointUrl);
            return response;

        } catch (EbmsException e) {
            throw e;
        } catch (Exception e) {
            Throwable rootCause = rootCause(e);
            log.error("[OUTBOUND] Verzending mislukt naar endpoint={} oorzaak={} bericht={}",
                endpointUrl, rootCause.getClass().getSimpleName(), rootCause.getMessage(), e);
            throw new EbmsException("CONNECTION_ERROR",
                "SOAP-verzending mislukt naar " + endpointUrl + ": " + describeFailure(e));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isHttps(String endpointUrl) {
        return endpointUrl != null && endpointUrl.toLowerCase().startsWith("https");
    }

    private long resolveConnectTimeoutMs() {
        return connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    private long resolveReadTimeoutMs() {
        return readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    private SOAPMessage invoke(String endpointUrl, String rawSoapXml, String cpaId, String toPartyId) {
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(resolveConnectTimeoutMs()));
            if (isHttps(endpointUrl)) {
                clientBuilder.sslContext(buildDynamicSslContext(cpaId, toPartyId));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl))
                .timeout(Duration.ofMillis(resolveReadTimeoutMs()))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", '"' + EBMS_SOAP_ACTION + '"')
                .POST(HttpRequest.BodyPublishers.ofString(rawSoapXml, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = clientBuilder.build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode < 200 || statusCode >= 300) {
                throw new EbmsException("HTTP_ERROR",
                    statusCode + " " + httpReason(statusCode) + " van partner endpoint " + endpointUrl);
            }

            if (statusCode == 204) {
                log.info("[OUTBOUND] HTTP 204 No Content ontvangen van endpoint={}; geen SOAP-response te parsen", endpointUrl);
                return null;
            }
            if (responseBody == null || responseBody.isBlank()) {
                throw new EbmsException("CONNECTION_ERROR",
                    "SOAP-response van " + endpointUrl + " was leeg (HTTP " + statusCode + ")");
            }

            return MessageFactory.newInstance().createMessage(
                new MimeHeaders(),
                new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        } catch (EbmsException e) {
            throw e;
        } catch (Exception e) {
            throw new EbmsException("CONNECTION_ERROR",
                "SOAP-response van " + endpointUrl + " kon niet worden gelezen: " + describeFailure(e));
        }
    }

    private String httpReason(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "HTTP Error";
        };
    }

    private Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String describeFailure(Throwable failure) {
        Throwable rootCause = rootCause(failure);
        String message = rootCause.getMessage();
        return rootCause.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /**
     * Bouwt de outbound mTLS {@link SSLContext} dynamisch op, op basis van het geldige
     * partnercertificaat dat real-time uit de CPA-registry ({@code cpa-service}) wordt
     * opgehaald – i.p.v. een statische truststore.
     *
     * <p>Het certificaat wordt als individuele trust-anchor toegevoegd aan een in-memory
     * PKCS12 keystore (certificate pinning per CPA-partij). De lokale client-keystore
     * (private key + certificaat van deze adapter) wordt, indien geconfigureerd via
     * {@link EbmsOutboundSSLProperties}, gecombineerd voor de mTLS-handshake.
     *
     * <p>Fail-closed: als er geen geldig certificaat gevonden wordt voor de CPA/partij,
     * of het opbouwen van de trust mislukt, wordt het bericht direct als mislukt beschouwd.
     *
     * @throws EbmsException als er geen geldig partnercertificaat gevonden wordt of de
     *                        trust niet opgebouwd kan worden
     */
    private SSLContext buildDynamicSslContext(String cpaId, String toPartyId) {
        List<PartnerCertificateDto> certificates =
            cpaValidationService.getPartnerCertificates(cpaId, toPartyId);

        try {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

            for (PartnerCertificateDto cert : certificates) {
                X509Certificate x509Certificate = (X509Certificate) certificateFactory.generateCertificate(
                    new ByteArrayInputStream(cert.getCertificatePem().getBytes(StandardCharsets.UTF_8)));
                trustStore.setCertificateEntry(cert.getCertificateAlias(), x509Certificate);
            }

            SSLContextBuilder sslContextBuilder = SSLContexts.custom()
                .loadTrustMaterial(trustStore, null);

            String keystorePath     = sslProperties.getKeystorePath();
            String keystorePassword = sslProperties.getKeystorePassword();
            if (keystorePath != null && !keystorePath.isBlank()
                    && keystorePassword != null && !keystorePassword.isBlank()) {
                char[] keyPass = keystorePassword.toCharArray();
                sslContextBuilder.loadKeyMaterial(new File(keystorePath), keyPass, keyPass);
                log.debug("[OUTBOUND] Client-keystore geladen voor mTLS: path={}", keystorePath);
            } else {
                log.warn("[OUTBOUND] Geen client-keystore geconfigureerd; server mTLS-authenticatie kan mislukken");
            }

            SSLContext sslContext = sslContextBuilder.build();
            log.info("[OUTBOUND] Dynamische mTLS trust opgebouwd: cpaId={} toPartyId={} certCount={}",
                cpaId, toPartyId, certificates.size());
            return sslContext;

        } catch (Exception e) {
            log.error("[OUTBOUND] Opbouwen dynamische mTLS trust mislukt: cpaId={} toPartyId={} - {}",
                cpaId, toPartyId, e.getMessage());
            throw new EbmsException("MTLS_TRUST_ERROR",
                "Kon geen dynamische mTLS trust opbouwen voor CPA=" + cpaId + " party=" + toPartyId
                    + ": " + e.getMessage());
        }
    }

}
