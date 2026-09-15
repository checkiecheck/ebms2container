package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import org.junit.jupiter.api.Test;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SoapHelperMessageHeaderOrderTest {

    private final SoapHelper soapHelper = new SoapHelper();

    @Test
    void buildOutboundSoapUsesOasisMessageHeaderSequence() throws Exception {
        SOAPMessage message = soapHelper.buildOutboundSoap(header(), false);
        SOAPElement messageHeader = (SOAPElement) message.getSOAPHeader()
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageHeader").item(0);

        assertThat(childNames(messageHeader))
            .containsExactly("From", "To", "CPAId", "ConversationId", "Service", "Action", "MessageData");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "From").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "From")).isEqualTo("Sender");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "To").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "To")).isEqualTo("Receiver");
    }

    @Test
    void createAckUsesOasisMessageHeaderSequenceAndReversesParties() throws Exception {
        SOAPMessage message = soapHelper.createAck(header());
        SOAPElement messageHeader = (SOAPElement) message.getSOAPHeader()
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageHeader").item(0);

        assertThat(childNames(messageHeader))
            .containsExactly("From", "To", "CPAId", "ConversationId", "Service", "Action", "MessageData");
        assertThat(messageHeader.getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "From")
            .item(0).getTextContent()).contains("receiver");
        assertThat(messageHeader.getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "To")
            .item(0).getTextContent()).contains("sender");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "From").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "From")).isEqualTo("Receiver");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "To").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "To")).isEqualTo("Sender");

        SOAPElement messageData = (SOAPElement) messageHeader
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageData").item(0);
        assertThat(childNames(messageData))
            .containsExactly("MessageId", "Timestamp", "RefToMessageId");
    }

    @Test
    void outboundMessageDataUsesMessageIdBeforeTimestamp() throws Exception {
        SOAPMessage message = soapHelper.buildOutboundSoap(header(), false);
        SOAPElement messageHeader = (SOAPElement) message.getSOAPHeader()
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageHeader").item(0);
        SOAPElement messageData = (SOAPElement) messageHeader
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageData").item(0);

        assertThat(childNames(messageData))
            .containsExactly("MessageId", "Timestamp");
    }

    @Test
    void pongUsesMessageDataAndOasisMessageHeaderSequence() throws Exception {
        SOAPMessage message = new PingEchoService(soapHelper).handlePing(header());
        SOAPElement messageHeader = (SOAPElement) message.getSOAPHeader()
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageHeader").item(0);

        assertThat(childNames(messageHeader))
            .containsExactly("From", "To", "CPAId", "ConversationId", "Service", "Action", "MessageData");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "From").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "From")).isEqualTo("Receiver");
        assertThat(childNames(messageHeader.getElementsByTagNameNS(
            SoapHelper.EBXML_MSG_NS, "To").item(0)))
            .containsExactly("PartyId", "Role");
        assertThat(roleText(messageHeader, "To")).isEqualTo("Sender");
        assertThat(messageHeader.getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageInfo")
            .getLength()).isZero();
    }

    @Test
    void generatedSoapValidatesAgainstOfficialOasisMessageHeaderSchema() throws Exception {
        SOAPMessage message = soapHelper.buildOutboundSoap(header(), false);
        String xml = soapToString(message);

        validateAgainstOfficialOasisSchema(xml);
    }

    @Test
    void parseIncomingMessageHeaderWithoutOptionalRolesKeepsRolesNull() throws Exception {
        EbxmlMessageHeader headerWithoutRoles = header();
        headerWithoutRoles.setFromRole(null);
        headerWithoutRoles.setToRole(null);
        SOAPMessage incomingMessage = soapHelper.buildOutboundSoap(headerWithoutRoles, false);

        EbxmlMessageHeader parsed = soapHelper.parseMessageHeader(incomingMessage.getSOAPHeader());

        assertThat(parsed).isNotNull();
        assertThat(parsed.getFrom()).extracting(PartyId::getValue).containsExactly("sender");
        assertThat(parsed.getTo()).extracting(PartyId::getValue).containsExactly("receiver");
        assertThat(parsed.getFromRole()).isNull();
        assertThat(parsed.getToRole()).isNull();
    }

    private EbxmlMessageHeader header() {
        return EbxmlMessageHeader.builder()
            .cpaId("cpa-1")
            .conversationId("conversation-1")
            .from(List.of(PartyId.builder().value("sender").build()))
            .fromRole("Sender")
            .to(List.of(PartyId.builder().value("receiver").build()))
            .toRole("Receiver")
            .service(ServiceType.builder().value("urn:test:service").build())
            .action("TestAction")
            .messageInfo(MessageInfo.builder()
                .messageId("message-1")
                .timestamp(Instant.parse("2026-09-14T00:00:00Z"))
                .build())
            .build();
    }

    private List<String> childNames(Object element) {
        List<String> names = new ArrayList<>();
        Iterator<?> children = ((SOAPElement) element).getChildElements();
        while (children.hasNext()) {
            Object child = children.next();
            if (child instanceof SOAPElement soapElement) {
                names.add(soapElement.getLocalName());
            }
        }
        return names;
    }

    private String roleText(SOAPElement messageHeader, String direction) {
        SOAPElement party = (SOAPElement) messageHeader
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, direction).item(0);
        return party.getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "Role")
            .item(0).getTextContent();
    }

    private void validateAgainstOfficialOasisSchema(String xml) throws Exception {
        URL schemaUrl = URI.create(
            "https://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd")
            .toURL();

        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(
            "http://apache.org/xml/features/validation/schema-full-checking", false);
        factory.setResourceResolver(new LSResourceResolver() {
            @Override
            public LSInput resolveResource(String type, String namespaceURI, String publicId,
                                          String systemId, String baseURI) {
                try {
                    if (systemId == null || systemId.isBlank()) {
                        return null;
                    }
                    String normalizedSystemId = systemId;
                    if (normalizedSystemId.startsWith("http://www.oasis-open.org/")) {
                        normalizedSystemId = "https://www.oasis-open.org/" + normalizedSystemId.substring("http://www.oasis-open.org/".length());
                    }
                    if (normalizedSystemId.startsWith("http://www.w3.org/")) {
                        normalizedSystemId = "https://www.w3.org/" + normalizedSystemId.substring("http://www.w3.org/".length());
                    }
                    if (normalizedSystemId.startsWith("http://schemas.xmlsoap.org/")) {
                        normalizedSystemId = "https://schemas.xmlsoap.org/" + normalizedSystemId.substring("http://schemas.xmlsoap.org/".length());
                    }

                    URL resolved = URI.create(normalizedSystemId).toURL();
                    InputStream inputStream = resolved.openStream();
                    LSInput input = new LSInputImpl();
                    input.setSystemId(resolved.toString());
                    input.setPublicId(publicId);
                    input.setByteStream(inputStream);
                    return input;
                } catch (Exception e) {
                    return null;
                }
            }
        });

        Schema schema = factory.newSchema(new StreamSource(schemaUrl.openStream(), schemaUrl.toString()));
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });

        Source source = new StreamSource(new StringReader(xml));
        validator.validate(source);
    }

    private String soapToString(SOAPMessage message) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        message.writeTo(baos);
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class LSInputImpl implements LSInput {
        private InputStream byteStream;
        private Reader characterStream;
        private String publicId;
        private String systemId;
        private String baseURI;
        private String encoding;
        private boolean certifiedText;
        private String stringData;

        @Override public InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(InputStream byteStream) { this.byteStream = byteStream; }
        @Override public Reader getCharacterStream() { return characterStream; }
        @Override public void setCharacterStream(Reader characterStream) { this.characterStream = characterStream; }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) { this.publicId = publicId; }
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { this.systemId = systemId; }
        @Override public String getBaseURI() { return baseURI; }
        @Override public void setBaseURI(String baseURI) { this.baseURI = baseURI; }
        @Override public String getEncoding() { return encoding; }
        @Override public void setEncoding(String encoding) { this.encoding = encoding; }
        @Override public boolean getCertifiedText() { return certifiedText; }
        @Override public void setCertifiedText(boolean certifiedText) { this.certifiedText = certifiedText; }
        @Override public String getStringData() { return stringData; }
        @Override public void setStringData(String stringData) { this.stringData = stringData; }
    }
}