package nl.logius.ebms.orchestrator.soap;

import jakarta.annotation.Resource;
import jakarta.xml.soap.SOAPHeader;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.*;
import jakarta.xml.ws.handler.MessageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.DuplicateMessageException;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import jakarta.xml.ws.handler.*;
import java.util.List;
import java.util.Map;

/**
 * JAX-WS {@code Provider<SOAPMessage>} endpoint voor het ontvangen van ebMS2-berichten.
 *
 * <p>Gebruik van {@link Provider}&lt;{@link SOAPMessage}&gt; (Message mode) geeft volledige
 * toegang tot het SOAP-bericht inclusief headers, body en MIME-attachments.
 *
 * <p>Geregistreerd via {@link nl.logius.ebms.orchestrator.config.CxfEndpointConfig}
 * op het CXF-pad {@code /services/ebms}.
 */
@WebServiceProvider(
    serviceName   = "MSHService",
    portName      = "MSHPort",
    targetNamespace = SoapHelper.EBXML_MSG_NS
)
@ServiceMode(value = Service.Mode.MESSAGE)
@Slf4j
@RequiredArgsConstructor
public class EbmsMessageProvider implements Provider<SOAPMessage> {

    private final OrchestratorService orchestratorService;
    private final PingEchoService     pingEchoService;
    private final SoapHelper          soapHelper;

    /** Injectie van WebServiceContext voor toegang tot HTTP-headers (OIN). */
    @Resource
    private WebServiceContext wsContext;

    @Override
    public SOAPMessage invoke(SOAPMessage request) {
        String clientOin = extractClientOin();

        try {
            SOAPHeader soapHeader = request.getSOAPHeader();
            if (soapHeader == null) {
                log.warn("SOAP-bericht zonder header ontvangen, clientOin={}", clientOin);
                return soapHelper.createErrorResponse(
                    "InvalidHeader", "SOAP Header ontbreekt", null);
            }

            EbxmlMessageHeader header = soapHelper.parseMessageHeader(soapHeader);
            if (header == null || header.getMessageInfo() == null) {
                return soapHelper.createErrorResponse(
                    "InvalidHeader", "ebXML MessageHeader ontbreekt of ongeldig", null);
            }

            header.setClientOin(clientOin);

            // Ping/Echo (ISO 15000-2 systeemservice)
            if (isPingRequest(header)) {
                log.info("[PING] van OIN={}", clientOin);
                return pingEchoService.handlePing(header);
            }

            // ebMS2 Acknowledgment (inkomende ACK op een rm-bericht dat wij stuurden)
            if (soapHelper.isAcknowledgment(soapHeader)) {
                String refToMessageId = soapHelper.parseRefToMessageId(soapHeader);
                log.info("[ACK] ontvangen: refToMessageId={} van OIN={}", refToMessageId, clientOin);
                return orchestratorService.handleAcknowledgment(refToMessageId);
            }

            // Reguliere ebMS2-berichtverwerking
            String rawSoap = soapHelper.soapToString(request);
            return orchestratorService.processInboundMessage(request, header, rawSoap, clientOin);

        } catch (DuplicateMessageException e) {
            log.warn("[DUPLICATE] {}", e.getMessage());
            return soapHelper.createErrorResponse("DuplicateElimination", e.getMessage(), null);
        } catch (Exception e) {
            log.error("Fout bij verwerking ebMS2 bericht (clientOin={})", clientOin, e);
            return soapHelper.createErrorResponse(
                "Unknown", "Interne verwerkingsfout: " + e.getMessage(), null);
        }
    }

    // ── OIN-extractie ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractClientOin() {
        try {
            if (wsContext == null) return null;
            MessageContext mc = wsContext.getMessageContext();
            Map<String, List<String>> httpHeaders =
                (Map<String, List<String>>) mc.get(MessageContext.HTTP_REQUEST_HEADERS);
            if (httpHeaders == null) return null;
            List<String> oinHeader = httpHeaders.get("X-Forwarded-Client-OIN");
            if (oinHeader == null || oinHeader.isEmpty()) return null;
            return oinHeader.get(0).strip();
        } catch (Exception e) {
            log.debug("OIN-header kon niet worden geëxtraheerd: {}", e.getMessage());
            return null;
        }
    }

    private boolean isPingRequest(EbxmlMessageHeader header) {
        return header.getService() != null
            && SoapHelper.EBXML_PING_SERVICE.equals(header.getService().getValue())
            && "Ping".equalsIgnoreCase(header.getAction());
    }
}
