package nl.logius.ebms.orchestrator.soap;

import com.sun.net.httpserver.HttpServer;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.xml.soap.SOAPMessage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboundSoapClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void send_setsSoapActionFromEbmsAction() throws Exception {
        AtomicReference<String> soapAction = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ebms", exchange -> {
            soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
            byte[] response = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        SoapHelper soapHelper = new SoapHelper();
        EbxmlMessageHeader header = EbxmlMessageHeader.builder()
            .cpaId("cpa-1")
            .conversationId("conversation-1")
            .from(List.of(PartyId.builder().value("from").build()))
            .to(List.of(PartyId.builder().value("to").build()))
            .service(ServiceType.builder().value("urn:test:service").build())
            .action("TestAction")
            .messageInfo(MessageInfo.builder()
                .messageId("message-1")
                .timestamp(Instant.now())
                .build())
            .build();
        SOAPMessage message = soapHelper.buildOutboundSoap(header, false);

        OutboundSoapClient client = new OutboundSoapClient(
            new EbmsOutboundSSLProperties(), mock(CpaValidationService.class), soapHelper);
        client.send(
            "http://localhost:" + server.getAddress().getPort() + "/ebms",
            soapHelper.soapToString(message),
            "cpa-1",
            "to");

        assertThat(soapAction.get()).isEqualTo("\"TestAction\"");
    }
}