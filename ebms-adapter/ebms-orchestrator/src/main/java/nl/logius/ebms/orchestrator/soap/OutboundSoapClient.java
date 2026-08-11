package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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
 *   <li>mTLS: optionele injectie van {@link SSLContext} voor two-way TLS
 *       (PKIoverheid-certificaten, ingeladen via de crypto-service).</li>
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

    /** Optionele mTLS SSLContext (injecteert PKIoverheid TLS-certificaat). */
    private final SSLContext mTlsSslContext;

    /**
     * Constructor – SSLContext is optioneel; zonder mTLS wordt plain-HTTP gebruikt.
     * In productie injecteer je een {@link SSLContext} via een
     * {@code @Configuration}-klasse die het PKIoverheid-certificaat laadt.
     */
    public OutboundSoapClient(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            SSLContext mTlsSslContext) {
        this.mTlsSslContext = mTlsSslContext;
        if (mTlsSslContext == null) {
            log.warn("[OUTBOUND] Geen mTLS SSLContext geconfigureerd – plain HTTP gebruikt");
        }
    }

    /**
     * Verstuurt een (gesigneerd en/of versleuteld) SOAP-bericht naar het opgegeven endpoint.
     *
     * <p>Digikoppeling-compliant: SOAP 1.1, Message mode, zonder WSDL.
     * Bij een SOAP Fault of verbindingsfout wordt een {@link EbmsException} gegooid.
     *
     * @param endpointUrl het HTTPS-endpoint van de ketenpartner (uit CPA)
     * @param rawSoapXml  het volledig geserialiseerde SOAP-bericht als UTF-8 string
     * @return het SOAP-antwoord van de partner (ACK, Pong of Error)
     * @throws EbmsException bij verbindingsfout of SOAP Fault
     */
    public SOAPMessage send(String endpointUrl, String rawSoapXml) {
        log.info("[OUTBOUND] Verzenden naar endpoint={}", endpointUrl);
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

            // ── 4. mTLS configureren (indien SSLContext beschikbaar) ───────
            if (mTlsSslContext != null) {
                configureMtls(dispatch);
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
     * Configureert mTLS via SSLContext injectie in de CXF HTTPConduit.
     *
     * <p>Sovereign WAN-extensie: de SSLContext laadt het PKIoverheid TLS-certificaat
     * dat real-time wordt opgehaald via de {@code CryptoServiceClient}.
     */
    private void configureMtls(Dispatch<SOAPMessage> dispatch) {
        try {
            Client client = ClientProxy.getClient(dispatch);
            HTTPConduit conduit = (HTTPConduit) client.getConduit();
            org.apache.cxf.configuration.jsse.TLSClientParameters tlsParams =
                new org.apache.cxf.configuration.jsse.TLSClientParameters();
            tlsParams.setSSLSocketFactory(mTlsSslContext.getSocketFactory());
            conduit.setTlsClientParameters(tlsParams);
            log.debug("[OUTBOUND] mTLS geconfigureerd via SSLContext");
        } catch (Exception e) {
            log.warn("[OUTBOUND] mTLS-configuratie mislukt (gebruik plain-TLS): {}", e.getMessage());
        }
    }
}
