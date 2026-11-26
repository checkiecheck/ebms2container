package nl.logius.ebms.orchestrator.soap;

import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.service.PingSendingService;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Controleert op de draad (echte CXF/Jetty) dat een GET netjes met 405 wordt afgewezen. */
class EbmsMessageProviderHttpStatusTest {

    private Server server;
    private String endpointUrl;

    @BeforeEach
    void setUp() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        endpointUrl = "http://localhost:" + port + "/services/ebms";
        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setAddress(endpointUrl);
        factory.setServiceBean(new EbmsMessageProvider(mock(OrchestratorService.class), mock(PingEchoService.class),
            mock(PingSendingService.class), mock(CpaValidationService.class), new SoapHelper()));
        server = factory.create();
    }

    @AfterEach
    void tearDown() {
        server.destroy();
    }

    @Test
    void get_returnsHttp405WithAllowHeader() throws Exception {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(endpointUrl)).GET().build());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("POST");
    }

    private static HttpResponse<String> call(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
