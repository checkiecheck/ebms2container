package nl.logius.ebms.orchestrator.soap;

import jakarta.annotation.Resource;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.Provider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.WebServiceProvider;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CxfHttpResponseCodeInterceptorTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.destroy();
        }
    }

    @Test
    void nullProviderResponse_withExplicit204_returnsHttp204OnWire() throws Exception {
        String endpointUrl = "http://localhost:" + pickFreePort() + "/services/ebms";
        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setAddress(endpointUrl);
        factory.setServiceBean(new NoContentProvider());
        server = factory.create();

        HttpResponse<String> response = postSoap(endpointUrl);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    private HttpResponse<String> postSoap(String endpointUrl) throws Exception {
        String soap = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
              <SOAP-ENV:Header/>
              <SOAP-ENV:Body/>
            </SOAP-ENV:Envelope>
            """;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"\"")
            .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8))
            .build();
        return HttpClient.newHttpClient().send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int pickFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @WebServiceProvider(
        serviceName = "NoContentService",
        portName = "NoContentPort",
        targetNamespace = "http://test.local/"
    )
    @ServiceMode(Service.Mode.MESSAGE)
    public static class NoContentProvider implements Provider<SOAPMessage> {

        @Resource
        private WebServiceContext webServiceContext;

        @Override
        public SOAPMessage invoke(SOAPMessage request) {
            webServiceContext.getMessageContext().put(MessageContext.HTTP_RESPONSE_CODE, 204);
            HttpServletResponse response = (HttpServletResponse) webServiceContext
                .getMessageContext().get(MessageContext.SERVLET_RESPONSE);
            response.setStatus(204);
            try {
                response.flushBuffer();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Kan HTTP 204-response niet committen", e);
            }
            return null;
        }
    }
}