package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Service;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Apache CXF {@link Dispatch}&lt;{@link SOAPMessage}&gt; client voor het versturen van
 * ondertekende en/of versleutelde ebMS2-enveloppen (SOAP 1.1, Message mode).
 *
 * <p>Design-keuzes:
 * <ul>
 *   <li>Geen WSDL vereist: dynamische poort-binding via {@link Service#addPort} (flexibel
 *       voor de 29 gemeentelijke koppelingen zonder vooraf-compileren).</li>
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

    private static final QName SERVICE_NAME =
        new QName(SoapHelper.EBXML_MSG_NS, "MSHService");
    private static final QName PORT_NAME =
        new QName(SoapHelper.EBXML_MSG_NS, "MSHPort");

    @Value("${ebms.outbound.connect-timeout-ms:10000}")
    private long connectTimeoutMs;

    @Value("${ebms.outbound.read-timeout-ms:30000}")
    private long readTimeoutMs;

    private final EbmsOutboundSSLProperties sslProperties;
    private final CpaValidationService cpaValidationService;

    public OutboundSoapClient(EbmsOutboundSSLProperties sslProperties,
                               CpaValidationService cpaValidationService) {
        this.sslProperties = sslProperties;
        this.cpaValidationService = cpaValidationService;
    }

    /**
     * Verstuurt een (gesigneerd en/of versleuteld) SOAP-bericht naar het opgegeven endpoint.
     *
     * <p>Digikoppeling-compliant: SOAP 1.1, Message mode, zonder WSDL. Voor HTTPS-endpoints
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
            SOAPMessage soapMessage = MessageFactory.newInstance().createMessage(
                new MimeHeaders(),
                new ByteArrayInputStream(rawSoapXml.getBytes(StandardCharsets.UTF_8)));

            // ── 2. CXF Dispatch aanmaken (geen WSDL vereist) ──────────────
            Service service = Service.create(SERVICE_NAME);
            service.addPort(PORT_NAME,
                jakarta.xml.ws.soap.SOAPBinding.SOAP11HTTP_BINDING,
                endpointUrl);

            Dispatch<SOAPMessage> dispatch = service.createDispatch(
                PORT_NAME,
                SOAPMessage.class,
                Service.Mode.MESSAGE);

            // ── 3. Timeouts instellen via CXF HTTPConduit ─────────────────
            configureTimeouts(dispatch);

            // ── 4. mTLS configureren (dynamische CPA-trust, alleen voor HTTPS) ─
            if (isHttps(endpointUrl)) {
                SSLContext dynamicSslContext = buildDynamicSslContext(cpaId, toPartyId);
                configureMtls(dispatch, dynamicSslContext);
            }

            // ── 5. Bericht verzenden ──────────────────────────────────────
            SOAPMessage response = dispatch.invoke(soapMessage);

            // ── 6. SOAP Fault check ───────────────────────────────────────
            if (response != null && response.getSOAPBody() != null
                    && response.getSOAPBody().hasFault()) {
                String faultString = response.getSOAPBody().getFault().getFaultString();
                log.error("[OUTBOUND] SOAP Fault ontvangen van {}: {}", endpointUrl, faultString);
                throw new EbmsException("SOAP_FAULT",
                    "SOAP Fault van partner endpoint (" + endpointUrl + "): " + faultString);
            }

            log.info("[OUTBOUND] Verzending geslaagd naar endpoint={}", endpointUrl);
            return response;

        } catch (EbmsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[OUTBOUND] Verzending mislukt naar endpoint={}: {}", endpointUrl, e.getMessage());
            throw new EbmsException("CONNECTION_ERROR",
                "SOAP-verzending mislukt naar " + endpointUrl + ": " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isHttps(String endpointUrl) {
        return endpointUrl != null && endpointUrl.toLowerCase().startsWith("https");
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

    /**
     * Configureert connect- en read-timeouts via de CXF HTTPConduit.
     * Voorkomt thread-exhaustion bij trage externe overheidsvoorzieningen (BIO vereiste).
     */
    private void configureTimeouts(Dispatch<SOAPMessage> dispatch) {
        try {
            Client client = ClientProxy.getClient(dispatch);
            HTTPConduit conduit = (HTTPConduit) client.getConduit();
            HTTPClientPolicy policy = new HTTPClientPolicy();
            policy.setConnectionTimeout(connectTimeoutMs);
            policy.setReceiveTimeout(readTimeoutMs);
            conduit.setClient(policy);
            log.debug("[OUTBOUND] Timeouts: connect={}ms read={}ms", connectTimeoutMs, readTimeoutMs);
        } catch (Exception e) {
            // Fallback: stel via request context in (werkt voor JAX-WS RI)
            log.warn("[OUTBOUND] CXF HTTPConduit niet beschikbaar – fallback timeout: {}", e.getMessage());
            dispatch.getRequestContext().put("javax.xml.ws.client.connectionTimeout", connectTimeoutMs);
            dispatch.getRequestContext().put("javax.xml.ws.client.receiveTimeout", readTimeoutMs);
        }
    }

    /**
     * Configureert mTLS via SSLContext-injectie in de CXF HTTPConduit.
     *
     * <p>Fail-closed: als de injectie mislukt, wordt de verzending afgebroken i.p.v. stilletjes
     * terug te vallen op plain TLS (voorkomt het versturen van gevoelige data zonder mTLS).
     */
    private void configureMtls(Dispatch<SOAPMessage> dispatch, SSLContext sslContext) {
        try {
            Client client = ClientProxy.getClient(dispatch);
            HTTPConduit conduit = (HTTPConduit) client.getConduit();
            org.apache.cxf.configuration.jsse.TLSClientParameters tlsParams =
                new org.apache.cxf.configuration.jsse.TLSClientParameters();
            tlsParams.setSSLSocketFactory(sslContext.getSocketFactory());
            conduit.setTlsClientParameters(tlsParams);
            log.debug("[OUTBOUND] mTLS geconfigureerd via dynamische SSLContext");
        } catch (Exception e) {
            log.error("[OUTBOUND] mTLS-configuratie mislukt: {}", e.getMessage());
            throw new EbmsException("MTLS_CONFIG_ERROR", "mTLS-configuratie mislukt: " + e.getMessage());
        }
    }
}
