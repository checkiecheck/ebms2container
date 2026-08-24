package nl.logius.ebms.orchestrator.config;

import org.apache.cxf.Bus;
import org.apache.cxf.jaxws.EndpointImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.soap.EbmsMessageProvider;
import nl.logius.ebms.orchestrator.soap.PingEchoService;
import nl.logius.ebms.orchestrator.soap.RawPayloadCaptureInterceptor;
import nl.logius.ebms.orchestrator.soap.SoapHelper;

import jakarta.xml.ws.Endpoint;

/**
 * Registreert de CXF SOAP-endpoints op de CXF-bus.
 *
 * <p>Gepubliceerde paden (relatief aan {@code cxf.path=/services}):
 * <ul>
 *   <li>{@code /services/ebms}  – hoofd ebMS2 SOAP endpoint</li>
 * </ul>
 *
 * <p>De service-lijst is beschikbaar op {@code /services}.
 */
@Configuration
public class CxfEndpointConfig {

    @Autowired
    private Bus cxfBus;

    /**
     * Instantieer de {@link EbmsMessageProvider} als Spring-beheerde bean.
     * Constructor-injectie zorgt voor correcte afhankelijkheden.
     */
    @Bean
    public EbmsMessageProvider ebmsMessageProvider(OrchestratorService orchestratorService,
                                                    PingEchoService pingEchoService,
                                                    SoapHelper soapHelper) {
        return new EbmsMessageProvider(orchestratorService, pingEchoService, soapHelper);
    }

    /**
     * Registreer het ebMS2 SOAP-endpoint.
     * Het eindige URL is: {@code http://host:8080/services/ebms}
     */
    @Bean
    public Endpoint ebmsEndpoint(EbmsMessageProvider ebmsMessageProvider) {
        EndpointImpl endpoint = new EndpointImpl(cxfBus, ebmsMessageProvider);
        // Legt de rauwe HTTP-body vast vóór SAAJ-parsing (zie RawPayloadCaptureInterceptor javadoc) —
        // voorkomt digest-mismatches bij XML-DSig verificatie door SAAJ-herserialisatie.
        endpoint.getInInterceptors().add(new RawPayloadCaptureInterceptor());
        endpoint.publish("/ebms");
        return endpoint;
    }
}
