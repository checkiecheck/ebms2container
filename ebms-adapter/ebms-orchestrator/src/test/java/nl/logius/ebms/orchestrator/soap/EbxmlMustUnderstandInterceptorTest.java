package nl.logius.ebms.orchestrator.soap;

import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.xml.ws.Provider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPMessage;

import javax.xml.namespace.QName;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focus-tests voor {@link EbxmlMustUnderstandInterceptor} — validatie van de fix voor de
 * CXF-transportlaag bug waarbij CXF's ingebouwde MustUnderstandInterceptor élk inkomend
 * ebMS2-bericht afwees vóór het de applicatielogica bereikte.
 *
 * <p>Deze test spint een écht CXF JAX-WS Provider endpoint op een random localhost-poort op
 * (zonder Spring/DB/RabbitMQ) en doet raw HTTP POSTs met SOAP-enveloppen die
 * mustUnderstand="1" headers dragen in de OASIS ebMS_v2_0 msg-header-2_0.xsd namespace.
 * Dit is het enige test-niveau dat de bug daadwerkelijk reproduceert — in-process
 * OrchestratorService.processInboundMessage() aanroepen omzeilt de CXF-fasenketen.
 */
class EbxmlMustUnderstandInterceptorTest {

    private static final String EBXML_NS =
        "http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd";
    private static final String SOAP_ENV_NS =
        "http://schemas.xmlsoap.org/soap/envelope/";

    private Server server;
    private String endpointUrl;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.destroy();
            server = null;
        }
    }

    // ── Unit: getUnderstoodHeaders() contract ─────────────────────────────

    @Test
    @DisplayName("getUnderstoodHeaders() declares all 10 ebXML 2.0 QNames in the msg-header namespace")
    void understoodHeaders_containsAllTenEbxmlBlocks() {
        Set<QName> understood = new EbxmlMustUnderstandInterceptor().getUnderstoodHeaders();

        assertThat(understood).hasSize(10);
        // 4 die SoapHelper zelf uitzendt met mustUnderstand=1
        assertThat(understood).contains(
            new QName(EBXML_NS, "MessageHeader"),
            new QName(EBXML_NS, "AckRequested"),
            new QName(EBXML_NS, "Acknowledgment"),
            new QName(EBXML_NS, "ErrorList")
        );
        // 6 defensieve OASIS ebMS 2.0 headerblokken
        assertThat(understood).contains(
            new QName(EBXML_NS, "SyncReply"),
            new QName(EBXML_NS, "TraceHeaderList"),
            new QName(EBXML_NS, "Via"),
            new QName(EBXML_NS, "MessageOrder"),
            new QName(EBXML_NS, "StatusRequest"),
            new QName(EBXML_NS, "StatusResponse")
        );
        // Alles hoort thuis in de ebXML msg-header namespace — geen typo's naar andere NS
        assertThat(understood).allMatch(q -> EBXML_NS.equals(q.getNamespaceURI()));
    }

    // ── Real HTTP round-trip: bug reproduction & fix validation ───────────

    @Test
    @DisplayName("BUG REPRO: zonder interceptor wijst CXF een mustUnderstand=1 MessageHeader af met SOAP-Fault")
    void withoutInterceptor_realHttpPost_returnsMustUnderstandFault() throws Exception {
        AtomicBoolean providerCalled = new AtomicBoolean(false);
        startServer(providerCalled, /*withInterceptor=*/ false);

        HttpResponse<String> resp = postSoap(soapEnvelopeWith(true, false));

        // CXF SOAP 1.1 MustUnderstand fault → HTTP 500 en fault body
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("MustUnderstand");
        assertThat(resp.body()).containsIgnoringCase("not understood");
        // Belangrijk: de Provider werd NOOIT aangeroepen (bewijst dat CXF's
        // MustUnderstandInterceptor vóór de business logic afwijst).
        assertThat(providerCalled.get())
            .as("Provider.invoke() moet NIET zijn aangeroepen zonder de fix")
            .isFalse();
    }

    @Test
    @DisplayName("FIX: met EbxmlMustUnderstandInterceptor bereikt een mustUnderstand=1 MessageHeader de Provider")
    void withInterceptor_realHttpPost_reachesProvider_MessageHeaderOnly() throws Exception {
        AtomicBoolean providerCalled = new AtomicBoolean(false);
        startServer(providerCalled, /*withInterceptor=*/ true);

        HttpResponse<String> resp = postSoap(soapEnvelopeWith(true, false));

        // Ongeacht wat de Provider terugstuurt (hij kan zelf een fault genereren
        // wegens andere validatiestappen), het KRITIEKE punt is:
        //  1) geen "MustUnderstand ... not understood" fault meer, en
        //  2) de Provider is bereikt.
        assertThat(resp.body())
            .as("Response body mag geen MustUnderstand-fout meer bevatten")
            .doesNotContain("not understood");
        assertThat(providerCalled.get())
            .as("Provider.invoke() moet WEL zijn aangeroepen met de fix")
            .isTrue();
    }

    @Test
    @DisplayName("FIX: MessageHeader + AckRequested (beide mustUnderstand=1) bereiken samen de Provider")
    void withInterceptor_realHttpPost_reachesProvider_MessageHeaderPlusAckRequested() throws Exception {
        AtomicBoolean providerCalled = new AtomicBoolean(false);
        startServer(providerCalled, /*withInterceptor=*/ true);

        HttpResponse<String> resp = postSoap(soapEnvelopeWith(true, true));

        assertThat(resp.body()).doesNotContain("not understood");
        assertThat(providerCalled.get())
            .as("Provider.invoke() moet WEL zijn aangeroepen bij MessageHeader+AckRequested")
            .isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void startServer(AtomicBoolean providerCalled, boolean withInterceptor) throws Exception {
        int port = pickFreePort();
        endpointUrl = "http://localhost:" + port + "/services/ebms";

        JaxWsServerFactoryBean sf = new JaxWsServerFactoryBean();
        sf.setAddress(endpointUrl);
        sf.setServiceBean(new EchoProvider(providerCalled));
        if (withInterceptor) {
            sf.getInInterceptors().add(new EbxmlMustUnderstandInterceptor());
        }
        server = sf.create();
    }

    private HttpResponse<String> postSoap(String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"\"")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int pickFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static String soapEnvelopeWith(boolean messageHeader, boolean ackRequested) {
        StringBuilder h = new StringBuilder();
        if (messageHeader) {
            h.append("""
                <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                  <eb:From><eb:PartyId>urn:from</eb:PartyId></eb:From>
                  <eb:To><eb:PartyId>urn:to</eb:PartyId></eb:To>
                  <eb:CPAId>cpa-test</eb:CPAId>
                  <eb:ConversationId>c1</eb:ConversationId>
                  <eb:Service>urn:svc</eb:Service>
                  <eb:Action>Deliver</eb:Action>
                  <eb:MessageData>
                    <eb:MessageId>msg-001@test</eb:MessageId>
                    <eb:Timestamp>2026-01-01T00:00:00Z</eb:Timestamp>
                  </eb:MessageData>
                </eb:MessageHeader>
                """);
        }
        if (ackRequested) {
            h.append("""
                <eb:AckRequested SOAP-ENV:mustUnderstand="1" eb:version="2.0" eb:signed="false"/>
                """);
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
              <SOAP-ENV:Header>
                %s
              </SOAP-ENV:Header>
              <SOAP-ENV:Body/>
            </SOAP-ENV:Envelope>
            """.formatted(h.toString());
    }

    /** Minimale JAX-WS Provider-stub die alleen aftikt dat invoke() is aangeroepen. */
    @WebServiceProvider(
        serviceName = "EbmsService",
        portName = "EbmsPort",
        targetNamespace = "http://test.local/"
    )
    @ServiceMode(Service.Mode.MESSAGE)
    public static class EchoProvider implements Provider<SOAPMessage> {
        private final AtomicBoolean flag;

        public EchoProvider(AtomicBoolean flag) {
            this.flag = flag;
        }

        // No-arg constructor voor CXF's JAX-WS runtime (bean-instantiation als reflectie faalt)
        public EchoProvider() {
            this.flag = new AtomicBoolean(false);
        }

        @Override
        public SOAPMessage invoke(SOAPMessage request) {
            flag.set(true);
            try {
                // Retourneer een lege, geldige SOAP-response — hoeft geen ebMS-ack te zijn
                String empty = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                      <SOAP-ENV:Body/>
                    </SOAP-ENV:Envelope>
                    """;
                return MessageFactory.newInstance().createMessage(null,
                    new ByteArrayInputStream(empty.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
