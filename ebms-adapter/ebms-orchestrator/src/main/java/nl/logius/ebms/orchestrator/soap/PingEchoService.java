package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Afhandeling van Ping / Pong (Echo) conform ISO 15000-2, Section 7.1.
 *
 * <p>Wanneer de orchestrator een Ping-bericht ontvangt, stuurt hij een Pong
 * (Acknowledgment) terug. Dit dient als connectivity-check tussen partners.
 *
 * <pre>
 *   Verzender → [Ping]  → ebms-orchestrator
 *   Verzender ← [Pong]  ← ebms-orchestrator
 * </pre>
 *
 * Ping: Service = urn:oasis:names:tc:ebxml-msg:service, Action = Ping
 * Pong: Service = urn:oasis:names:tc:ebxml-msg:service, Action = Pong
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PingEchoService {

    private final SoapHelper soapHelper;

    /**
     * Verwerkt een Ping-request en construeert een Pong-response.
     *
     * @param pingHeader het geparsede MessageHeader van het Ping-bericht
     * @return SOAP Pong-response
     */
    public SOAPMessage handlePing(EbxmlMessageHeader pingHeader) {
        String refToMsgId = pingHeader.getMessageInfo().getMessageId();
        log.info("[PING-ECHO] RefToMessageId={} van {}",
            refToMsgId,
            pingHeader.getFrom().isEmpty() ? "onbekend" : pingHeader.getFrom().get(0).getValue());

        try {
            MessageFactory mf = MessageFactory.newInstance();
            SOAPMessage pong  = mf.createMessage();
            SOAPEnvelope env  = pong.getSOAPPart().getEnvelope();
            SOAPHeader   header = pong.getSOAPHeader();

            // MessageHeader voor de Pong
            SOAPElement msgHeader = header.addChildElement("MessageHeader", "eb",
                SoapHelper.EBXML_MSG_NS);
            msgHeader.addAttribute(
                env.createName("mustUnderstand", "SOAP-ENV", SoapHelper.SOAP_ENV_NS), "1");
            msgHeader.addAttribute(
                env.createName("version", "eb", SoapHelper.EBXML_MSG_NS), "2.0");

            // From / To worden omgedraaid (wij zijn nu de verzender)
            addPartyElement(msgHeader, env, "From", pingHeader.getTo());
            addPartyElement(msgHeader, env, "To",   pingHeader.getFrom());

            addText(msgHeader, "CPAId",          pingHeader.getCpaId());
            addText(msgHeader, "ConversationId", pingHeader.getConversationId());
            addText(msgHeader, "Service",        SoapHelper.EBXML_PING_SERVICE);
            addText(msgHeader, "Action",         "Pong");

            // MessageInfo
            SOAPElement msgInfo = msgHeader.addChildElement("MessageInfo", "eb",
                SoapHelper.EBXML_MSG_NS);
            addText(msgInfo, "Timestamp",      Instant.now().toString());
            addText(msgInfo, "MessageId",      UUID.randomUUID() + "@ebms-orchestrator");
            addText(msgInfo, "RefToMessageId", refToMsgId);

            pong.saveChanges();
            log.debug("[PONG] verzonden als antwoord op Ping {}", refToMsgId);
            return pong;

        } catch (SOAPException e) {
            log.error("Fout bij aanmaken Pong-response", e);
            return soapHelper.createEmptyResponse();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void addPartyElement(SOAPElement parent, SOAPEnvelope env,
                                  String direction, List<PartyId> parties)
            throws SOAPException {
        SOAPElement dirEl = parent.addChildElement(direction, "eb", SoapHelper.EBXML_MSG_NS);
        for (PartyId pid : parties) {
            SOAPElement partyIdEl = dirEl.addChildElement("PartyId", "eb", SoapHelper.EBXML_MSG_NS);
            partyIdEl.addTextNode(pid.getValue());
            if (pid.getType() != null && !pid.getType().isBlank()) {
                partyIdEl.addAttribute(
                    env.createName("type", "eb", SoapHelper.EBXML_MSG_NS), pid.getType());
            }
        }
    }

    private void addText(SOAPElement parent, String name, String text) throws SOAPException {
        if (text != null) {
            parent.addChildElement(name, "eb", SoapHelper.EBXML_MSG_NS).addTextNode(text);
        }
    }
}
