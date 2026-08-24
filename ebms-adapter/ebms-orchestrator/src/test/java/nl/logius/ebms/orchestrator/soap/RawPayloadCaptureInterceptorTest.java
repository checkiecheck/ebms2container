package nl.logius.ebms.orchestrator.soap;

import org.apache.cxf.jaxws.context.WrappedMessageContext;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.xml.ws.handler.MessageContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focus-tests voor {@link RawPayloadCaptureInterceptor}.
 *
 * <p>Verifieert de twee kritieke aannames van de fix:
 * <ol>
 *   <li>De interceptor legt de rauwe bytes vast op de {@link Exchange}
 *       en vervangt de {@link InputStream} op de {@link Message} door een
 *       verse, opnieuw-leesbare kopie (SAAJ-parsing werkt daarna nog).</li>
 *   <li>Een property die de interceptor via
 *       {@code message.getExchange().put(key, value)} plaatst, is later
 *       zichtbaar via de JAX-WS {@link MessageContext#get(Object)} — zoals
 *       geimplementeerd door CXF's {@link WrappedMessageContext} die bij
 *       een miss op de Message-map doorvraagt op de Exchange (bytecode-
 *       geverifieerd voor cxf-rt-frontend-jaxws 4.1.8).</li>
 * </ol>
 *
 * <p>Deze test omzeilt bewust de HTTP/Servlet-laag om deterministisch te
 * kunnen valideren zonder afhankelijkheid van Tomcat/Testcontainers.
 */
class RawPayloadCaptureInterceptorTest {

    private static final String RAW_XML =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
      + "<soap:Header/><soap:Body><TestPayload>hallo</TestPayload></soap:Body>"
      + "</soap:Envelope>";

    @Test
    @DisplayName("Interceptor plaatst rauwe XML op Exchange EN vervangt de InputStream door een leesbare kopie")
    void handleMessage_capturesRawPayloadAndReplacesStream() throws Exception {
        Message message = new MessageImpl();
        Exchange exchange = new ExchangeImpl();
        message.setExchange(exchange);
        message.setContent(InputStream.class,
            new ByteArrayInputStream(RAW_XML.getBytes(StandardCharsets.UTF_8)));

        new RawPayloadCaptureInterceptor().handleMessage(message);

        // (1) Rauwe XML zichtbaar op de Exchange
        Object stored = exchange.get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD);
        assertThat(stored)
            .as("Exchange bevat de rauwe payload onder RAW_XML_PAYLOAD")
            .isInstanceOf(String.class)
            .isEqualTo(RAW_XML);

        // (2) Stream is vervangen en volledig opnieuw leesbaar voor SAAJ
        InputStream replaced = message.getContent(InputStream.class);
        assertThat(replaced).as("stream is vervangen").isNotNull();
        String reread = new String(replaced.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(reread)
            .as("vervangen stream is opnieuw volledig leesbaar (geen truncatie/dubbele read-fout)")
            .isEqualTo(RAW_XML);
    }

    @Test
    @DisplayName("Property op Exchange is zichtbaar via WrappedMessageContext.get() – CXF 4.1.8 propagatie")
    void exchangeProperty_isVisibleViaWebServiceContext() throws Exception {
        Message message = new MessageImpl();
        Exchange exchange = new ExchangeImpl();
        message.setExchange(exchange);
        message.setContent(InputStream.class,
            new ByteArrayInputStream(RAW_XML.getBytes(StandardCharsets.UTF_8)));

        new RawPayloadCaptureInterceptor().handleMessage(message);

        // Simuleer wat CXF doet: JAX-WS Provider ziet de MessageContext als
        // een WrappedMessageContext rond dezelfde Message/Exchange.
        MessageContext mc = new WrappedMessageContext(message);

        Object viaContext = mc.get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD);
        assertThat(viaContext)
            .as("Exchange-property zichtbaar via JAX-WS MessageContext (WrappedMessageContext delegeert bij miss naar Exchange)")
            .isEqualTo(RAW_XML);
    }

    @Test
    @DisplayName("Geen InputStream aanwezig: interceptor loopt geruisloos door (bijv. bij lege of niet-HTTP transports)")
    void handleMessage_noInputStream_noException() {
        Message message = new MessageImpl();
        message.setExchange(new ExchangeImpl());
        // Geen setContent(InputStream.class, ...)

        new RawPayloadCaptureInterceptor().handleMessage(message);

        assertThat(message.getExchange().get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD))
            .as("Zonder inputstream wordt geen property gezet")
            .isNull();
    }

    @Test
    @DisplayName("Rauwe payload wordt byte-identiek vastgelegd (whitespace/formattering onaangetast)")
    void handleMessage_preservesExactBytesIncludingWhitespace() throws Exception {
        String pretty =
              "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
            + "  <soap:Header>\n"
            + "    <eb:MessageHeader xmlns:eb=\"http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd\">\n"
            + "      <eb:MessageId>uuid-abc-123</eb:MessageId>\n"
            + "    </eb:MessageHeader>\n"
            + "  </soap:Header>\n"
            + "  <soap:Body>   <TestPayload/>   </soap:Body>\n"
            + "</soap:Envelope>";

        Message message = new MessageImpl();
        message.setExchange(new ExchangeImpl());
        message.setContent(InputStream.class,
            new ByteArrayInputStream(pretty.getBytes(StandardCharsets.UTF_8)));

        new RawPayloadCaptureInterceptor().handleMessage(message);

        String captured = (String) message.getExchange().get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD);
        assertThat(captured)
            .as("XML-DSig-kritieke bytes (whitespace/regeleinden) blijven byte-identiek")
            .isEqualTo(pretty);
    }

    @Test
    @DisplayName("Hardening: InputStream die IOException gooit tijdens read → interceptor degradeert stil, geen Fault")
    void handleMessage_streamThrowsIOException_doesNotThrow() {
        Message message = new MessageImpl();
        message.setExchange(new ExchangeImpl());
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated read failure");
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated read failure");
            }
        };
        message.setContent(InputStream.class, failing);

        // Mag GEEN exception gooien - moet gracefully degraderen (WARN + early return)
        new RawPayloadCaptureInterceptor().handleMessage(message);

        assertThat(message.getExchange().get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD))
            .as("Bij read-fout wordt geen rauwe payload gezet (fallback naar soapToString() in Provider)")
            .isNull();
    }
}
