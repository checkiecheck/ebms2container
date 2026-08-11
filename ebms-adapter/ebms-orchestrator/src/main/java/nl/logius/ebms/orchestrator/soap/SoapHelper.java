package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.*;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.ebxml.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helper voor het parseren van ebXML SOAP-berichten en het construeren van
 * ebMS2-conforme SOAP-responses (ACK, Pong, Error).
 *
 * <p>Namespace: {@code http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd}
 */
@Component
@Slf4j
public class SoapHelper {

    public static final String EBXML_MSG_NS  =
        "http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd";
    public static final String SOAP_ENV_NS   =
        "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String EBXML_PING_SERVICE =
        "urn:oasis:names:tc:ebxml-msg:service";

    // ── MessageHeader parser ───────────────────────────────────────────────

    /**
     * Parseert het ebXML MessageHeader element uit de SOAP-header naar een intern DTO.
     *
     * @param soapHeader de SOAP-header
     * @return {@link EbxmlMessageHeader} of null als het element ontbreekt
     */
    public EbxmlMessageHeader parseMessageHeader(SOAPHeader soapHeader) throws SOAPException {
        NodeList elements = soapHeader.getElementsByTagNameNS(EBXML_MSG_NS, "MessageHeader");
        if (elements.getLength() == 0) {
            log.warn("Geen MessageHeader gevonden in SOAP-header");
            return null;
        }
        Element mh = (Element) elements.item(0);

        return EbxmlMessageHeader.builder()
            .cpaId(getChildText(mh, "CPAId"))
            .conversationId(getChildText(mh, "ConversationId"))
            .from(parsePartyIds(mh, "From"))
            .fromRole(getChildText(getChild(mh, "From"), "Role"))
            .to(parsePartyIds(mh, "To"))
            .toRole(getChildText(getChild(mh, "To"), "Role"))
            .service(parseService(mh))
            .action(getChildText(mh, "Action"))
            .messageInfo(parseMessageInfo(mh))
            .ackRequested(parseAckRequested(soapHeader))
            .build();
    }

    // ── Response factory-methoden ─────────────────────────────────────────

    /** Construeert een ebMS2-conforme SOAP ACK-response. */
    public SOAPMessage createAck(EbxmlMessageHeader originalHeader) {
        try {
            MessageFactory mf = MessageFactory.newInstance();
            SOAPMessage ack   = mf.createMessage();
            SOAPEnvelope env  = ack.getSOAPPart().getEnvelope();
            SOAPHeader   header = ack.getSOAPHeader();

            // Voeg MessageHeader toe voor de ACK
            SOAPElement msgHeader = header.addChildElement("MessageHeader", "eb", EBXML_MSG_NS);
            msgHeader.addAttribute(env.createName("mustUnderstand", "SOAP-ENV", SOAP_ENV_NS), "1");
            msgHeader.addAttribute(env.createName("version", "eb", EBXML_MSG_NS), "2.0");

            // Service = urn:oasis:names:tc:ebxml-msg:service (system service)
            addChild(msgHeader, "Service", EBXML_MSG_NS, EBXML_PING_SERVICE);
            addChild(msgHeader, "Action",  EBXML_MSG_NS, "Acknowledgment");

            // MessageInfo
            SOAPElement msgInfo = msgHeader.addChildElement("MessageInfo", "eb", EBXML_MSG_NS);
            addChild(msgInfo, "Timestamp",      EBXML_MSG_NS, Instant.now().toString());
            addChild(msgInfo, "MessageId",      EBXML_MSG_NS,
                UUID.randomUUID() + "@ebms-orchestrator");
            addChild(msgInfo, "RefToMessageId", EBXML_MSG_NS,
                originalHeader.getMessageInfo().getMessageId());

            // Acknowledgment element
            SOAPElement acknowledgment = header.addChildElement("Acknowledgment", "eb", EBXML_MSG_NS);
            acknowledgment.addAttribute(
                env.createName("mustUnderstand", "SOAP-ENV", SOAP_ENV_NS), "1");
            addChild(acknowledgment, "Timestamp",      EBXML_MSG_NS, Instant.now().toString());
            addChild(acknowledgment, "RefToMessageId", EBXML_MSG_NS,
                originalHeader.getMessageInfo().getMessageId());

            ack.saveChanges();
            return ack;

        } catch (SOAPException e) {
            log.error("Fout bij aanmaken ACK-response", e);
            return createEmptyResponse();
        }
    }

    /** Construeert een lege SOAP-response (Best Effort – geen ACK vereist). */
    public SOAPMessage createEmptyResponse() {
        try {
            return MessageFactory.newInstance().createMessage();
        } catch (SOAPException e) {
            throw new RuntimeException("Kan lege SOAP-response niet aanmaken", e);
        }
    }

    /** Construeert een ebMS2 SOAP-faultbericht. */
    public SOAPMessage createErrorResponse(String errorCode, String errorDescription,
                                            String refToMessageId) {
        try {
            MessageFactory mf = MessageFactory.newInstance();
            SOAPMessage msg   = mf.createMessage();
            SOAPHeader  header = msg.getSOAPHeader();

            SOAPElement errorList = header.addChildElement("ErrorList", "eb", EBXML_MSG_NS);
            errorList.addAttribute(
                msg.getSOAPPart().getEnvelope().createName("mustUnderstand", "SOAP-ENV", SOAP_ENV_NS), "1");

            SOAPElement error = errorList.addChildElement("Error", "eb", EBXML_MSG_NS);
            error.addAttribute(
                msg.getSOAPPart().getEnvelope().createName("errorCode", "eb", EBXML_MSG_NS), errorCode);
            error.addAttribute(
                msg.getSOAPPart().getEnvelope().createName("severity", "eb", EBXML_MSG_NS), "Error");
            if (refToMessageId != null) {
                error.addAttribute(
                    msg.getSOAPPart().getEnvelope().createName("refToMessageInError", "eb", EBXML_MSG_NS),
                    refToMessageId);
            }
            SOAPElement desc = error.addChildElement("Description", "eb", EBXML_MSG_NS);
            desc.addTextNode(errorDescription);

            msg.saveChanges();
            return msg;

        } catch (SOAPException e) {
            log.error("Fout bij aanmaken ErrorList-response", e);
            return createEmptyResponse();
        }
    }

    /** Converteert een SOAP-bericht naar een XML-string (voor logging/opslag). */
    public String soapToString(SOAPMessage message) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            message.writeTo(baos);
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Kan SOAP-bericht niet naar string serialiseren: {}", e.getMessage());
            return "<serialization-error/>";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private List<PartyId> parsePartyIds(Element messageHeader, String parentElement) {
        List<PartyId> ids = new ArrayList<>();
        Element parent = getChild(messageHeader, parentElement);
        if (parent == null) return ids;
        NodeList partyIdNodes = parent.getElementsByTagNameNS(EBXML_MSG_NS, "PartyId");
        for (int i = 0; i < partyIdNodes.getLength(); i++) {
            Element el = (Element) partyIdNodes.item(i);
            ids.add(PartyId.builder()
                .value(el.getTextContent().trim())
                .type(el.getAttributeNS(EBXML_MSG_NS, "type"))
                .build());
        }
        return ids;
    }

    private ServiceType parseService(Element messageHeader) {
        Element el = getChild(messageHeader, "Service");
        if (el == null) return null;
        return ServiceType.builder()
            .value(el.getTextContent().trim())
            .type(el.getAttributeNS(EBXML_MSG_NS, "type"))
            .build();
    }

    private MessageInfo parseMessageInfo(Element messageHeader) {
        Element mi = getChild(messageHeader, "MessageInfo");
        if (mi == null) return null;
        String ts = getChildText(mi, "Timestamp");
        return MessageInfo.builder()
            .timestamp(ts != null ? Instant.parse(ts) : Instant.now())
            .messageId(getChildText(mi, "MessageId"))
            .refToMessageId(getChildText(mi, "RefToMessageId"))
            .build();
    }

    private AckRequested parseAckRequested(SOAPHeader soapHeader) {
        NodeList nl = soapHeader.getElementsByTagNameNS(EBXML_MSG_NS, "AckRequested");
        if (nl.getLength() == 0) return null;
        Element el = (Element) nl.item(0);
        return AckRequested.builder()
            .signed(Boolean.parseBoolean(el.getAttributeNS(EBXML_MSG_NS, "signed")))
            .actor(el.getAttribute("actor"))
            .mustUnderstand(true)
            .build();
    }

    private Element getChild(Element parent, String localName) {
        if (parent == null) return null;
        NodeList nl = parent.getElementsByTagNameNS(EBXML_MSG_NS, localName);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    private String getChildText(Element parent, String localName) {
        Element child = getChild(parent, localName);
        return child != null ? child.getTextContent().trim() : null;
    }

    private void addChild(SOAPElement parent, String name, String ns, String text)
            throws SOAPException {
        SOAPElement el = parent.addChildElement(name, "eb", ns);
        if (text != null) el.addTextNode(text);
    }
}
