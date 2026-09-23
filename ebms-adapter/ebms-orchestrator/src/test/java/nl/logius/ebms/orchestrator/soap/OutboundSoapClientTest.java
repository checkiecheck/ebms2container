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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void send_setsFixedEbmsSoapAction() throws Exception {
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

        assertThat(soapAction.get()).isEqualTo("\"ebXML\"");
    }

    @Test
    void send_noContentResponse_isTreatedAsSuccessfulDelivery() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ebms", exchange -> {
            exchange.sendResponseHeaders(204, -1);
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
                .messageId("message-2")
                .timestamp(Instant.now())
                .build())
            .build();
        SOAPMessage message = soapHelper.buildOutboundSoap(header, false);

        OutboundSoapClient client = new OutboundSoapClient(
            new EbmsOutboundSSLProperties(), mock(CpaValidationService.class), soapHelper);

        SOAPMessage response = client.send(
            "http://localhost:" + server.getAddress().getPort() + "/ebms",
            soapHelper.soapToString(message),
            "cpa-1",
            "to");

        assertThat(response).isNull();
    }

    @Test
    void send_emptyOkResponseBody_isTreatedAsConnectionError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ebms", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
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
                .messageId("message-3")
                .timestamp(Instant.now())
                .build())
            .build();
        SOAPMessage message = soapHelper.buildOutboundSoap(header, false);

        OutboundSoapClient client = new OutboundSoapClient(
            new EbmsOutboundSSLProperties(), mock(CpaValidationService.class), soapHelper);

        assertThatThrownBy(() -> client.send(
                "http://localhost:" + server.getAddress().getPort() + "/ebms",
                soapHelper.soapToString(message),
                "cpa-1",
                "to"))
            .isInstanceOf(nl.logius.ebms.common.exception.EbmsException.class)
            .satisfies(ex -> assertThat(((nl.logius.ebms.common.exception.EbmsException) ex).getErrorCode())
                .isEqualTo("CONNECTION_ERROR"));
    }

    @Test
    void send_notFoundResponse_preservesHttpStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ebms", exchange -> {
            byte[] response = "Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        SoapHelper soapHelper = new SoapHelper();
        OutboundSoapClient client = new OutboundSoapClient(
            new EbmsOutboundSSLProperties(), mock(CpaValidationService.class), soapHelper);

        assertThatThrownBy(() -> client.send(
                "http://localhost:" + server.getAddress().getPort() + "/ebms",
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>",
                "cpa-1",
                "to"))
            .isInstanceOf(nl.logius.ebms.common.exception.EbmsException.class)
            .satisfies(ex -> {
                nl.logius.ebms.common.exception.EbmsException ebmsException =
                    (nl.logius.ebms.common.exception.EbmsException) ex;
                assertThat(ebmsException.getErrorCode()).isEqualTo("HTTP_ERROR");
                assertThat(ebmsException.getMessage()).contains("404 Not Found");
            });
    }
}