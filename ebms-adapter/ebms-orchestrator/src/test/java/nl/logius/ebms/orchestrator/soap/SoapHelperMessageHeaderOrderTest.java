package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import org.junit.jupiter.api.Test;

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

        assertThat(childNames(message.getSOAPHeader()
            .getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageHeader").item(0)))
            .containsExactly("From", "To", "CPAId", "ConversationId", "Service", "Action", "MessageData");
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
        assertThat(messageHeader.getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "MessageInfo")
            .getLength()).isZero();
    }

    private EbxmlMessageHeader header() {
        return EbxmlMessageHeader.builder()
            .cpaId("cpa-1")
            .conversationId("conversation-1")
            .from(List.of(PartyId.builder().value("sender").build()))
            .to(List.of(PartyId.builder().value("receiver").build()))
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
}