package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.soap.SOAPMessage;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

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
 *   <li>mTLS: bij HTTPS-endpoints levert {@link OutboundSslContextFactory} de SSLContext:
 *       het servercertificaat wordt gevalideerd tegen {@code truststore.p12} (en de JVM-truststore),
 *       het clientcertificaat is het eigen certificaat uit de CPA, gekoppeld aan een key in
 *       {@code keystore.p12}.</li>
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

    private final OutboundSslContextFactory sslContextFactory;
    private final SoapHelper soapHelper;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_READ_TIMEOUT_MS = 30_000L;

    public OutboundSoapClient(OutboundSslContextFactory sslContextFactory, SoapHelper soapHelper) {
        this.sslContextFactory = sslContextFactory;
        this.soapHelper = soapHelper;
    }

    /**
     * Verstuurt een (gesigneerd en/of versleuteld) SOAP-bericht naar het opgegeven endpoint.
     *
     * <p>Bij HTTPS wordt de mTLS-SSLContext opgebouwd via {@link OutboundSslContextFactory}.
     * Bij een SOAP Fault, ebXML ErrorList of verbindingsfout wordt een {@link EbmsException} gegooid.
     *
     * @param endpointUrl het HTTPS-endpoint van de ketenpartner (uit CPA)
     * @param rawSoapXml  het volledig geserialiseerde SOAP-bericht als UTF-8 string
     * @param cpaId       de CPA-identifier van het afleverkanaal
     * @param fromPartyId eigen partij-ID (bepaalt het mTLS-clientcertificaat uit de CPA)
     * @param toPartyId   partij-ID van de ontvanger
     * @return het SOAP-antwoord van de partner (ACK, Pong of Error)
     * @throws EbmsException bij verbindingsfout, mTLS-configuratiefout of SOAP Fault
     */
    public SOAPMessage send(String endpointUrl, String rawSoapXml, String cpaId,
                            String fromPartyId, String toPartyId) {
        log.info("[OUTBOUND] Verzenden naar endpoint={} cpaId={} fromPartyId={} toPartyId={}",
            endpointUrl, cpaId, fromPartyId, toPartyId);
        try {
            // ── 1. SOAPMessage reconstrueren uit string ────────────────────
            MessageFactory.newInstance().createMessage(
                new MimeHeaders(),
                new ByteArrayInputStream(rawSoapXml.getBytes(StandardCharsets.UTF_8)));

            // ── 2. Bericht verzenden ──────────────────────────────────────
            SOAPMessage response = invoke(endpointUrl, rawSoapXml, cpaId, fromPartyId, toPartyId);

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

    private SOAPMessage invoke(String endpointUrl, String rawSoapXml, String cpaId,
                               String fromPartyId, String toPartyId) {
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(resolveConnectTimeoutMs()));
            if (isHttps(endpointUrl)) {
                clientBuilder.sslContext(sslContextFactory.getSslContext(cpaId, fromPartyId, toPartyId));
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
}
