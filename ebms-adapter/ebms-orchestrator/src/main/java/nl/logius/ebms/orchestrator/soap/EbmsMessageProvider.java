package nl.logius.ebms.orchestrator.soap;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.soap.SOAPHeader;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.service.PingSendingService;
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
    private final PingSendingService  pingSendingService;
    private final CpaValidationService cpaValidationService;
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
                if (isAsyncPing(header)) {
                    pingSendingService.sendAsyncPong(header);
                    setHttpResponseCode(204);
                    return null;
                }
                return pingEchoService.handlePing(header);
            }

            // ebMS2 Acknowledgment (inkomende ACK op een rm-bericht dat wij stuurden)
            if (soapHelper.isAcknowledgment(soapHeader)) {
                String refToMessageId = soapHelper.parseRefToMessageId(soapHeader);
                String rawSoap = extractRawXmlPayload(request);
                boolean signed = soapHelper.hasSignature(request);
                String ackMessageId = header.getMessageInfo().getMessageId();
                String fromPartyId = header.getFrom().isEmpty() ? null : header.getFrom().get(0).getValue();
                log.info("[ACK] ontvangen: refToMessageId={} van OIN={}", refToMessageId, clientOin);
                return orchestratorService.handleAcknowledgment(
                    refToMessageId, rawSoap, ackMessageId, signed, header.getCpaId(), fromPartyId);
            }

            // Reguliere ebMS2-berichtverwerking
            String rawSoap = extractRawXmlPayload(request);
            return orchestratorService.processInboundMessage(request, header, rawSoap, clientOin);

        } catch (EbmsException e) {
            log.error("[SECURITY] Bericht afgewezen: errorCode={} msg={} clientOin={}",
                e.getErrorCode(), e.getMessage(), clientOin);
            return soapHelper.createErrorResponse(e.getErrorCode(), e.getMessage(), null);
        } catch (Exception e) {
            log.error("Fout bij verwerking ebMS2 bericht (clientOin={})", clientOin, e);
            return soapHelper.createErrorResponse(
                "Unknown", "Interne verwerkingsfout: " + e.getMessage(), null);
        }
    }

    // ── Rauwe payload-extractie ────────────────────────────────────────────

    /**
     * Haalt de ONBEWERKTE inkomende XML-payload op, vastgelegd door
     * {@link RawPayloadCaptureInterceptor} vóórdat SAAJ/CXF het bericht parseerde.
     *
     * <p>Kritiek voor XML-DSig verificatie: {@code soapHelper.soapToString(request)} serialiseert
     * het reeds geparseerde SAAJ-object opnieuw, wat een digest mismatch kan veroorzaken t.o.v.
     * de bytes die de verzendende partij daadwerkelijk stuurde. Valt terug op
     * {@code soapToString()} als de interceptor-property onverwacht ontbreekt.
     */
    private String extractRawXmlPayload(SOAPMessage request) {
        try {
            if (wsContext != null) {
                Object raw = wsContext.getMessageContext()
                    .get(RawPayloadCaptureInterceptor.RAW_XML_PAYLOAD);
                if (raw instanceof String rawXml && !rawXml.isBlank()) {
                    return rawXml;
                }
            }
        } catch (Exception e) {
            log.debug("Rauwe XML-payload niet beschikbaar in message context: {}", e.getMessage());
        }
        log.warn("[RAW-CAPTURE] Geen rauwe payload gevonden in message context - fallback naar "
            + "soapToString() (kan XML-DSig verificatie beïnvloeden)");
        return soapHelper.soapToString(request);
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

    /**
     * Alleen een expliciet opgehaalde CPA met syncReplyMode=none schakelt Ping naar async.
     * Bij elke lookupfout blijft de legacy synchrone Pong actief.
     */
    private boolean isAsyncPing(EbxmlMessageHeader header) {
        try {
            String fromPartyId = header.getFrom().isEmpty() ? null : header.getFrom().get(0).getValue();
            var channel = cpaValidationService.getDeliveryChannel(header.getCpaId(), fromPartyId);
            return channel != null && "none".equalsIgnoreCase(channel.getSyncReplyMode());
        } catch (Exception e) {
            log.warn("[PING] CPA-mode niet beschikbaar, synchrone Pong blijft actief: cpaId={} reden={}",
                header.getCpaId(), e.getMessage());
            return false;
        }
    }

    private void setHttpResponseCode(int statusCode) {
        if (wsContext != null) {
            MessageContext messageContext = wsContext.getMessageContext();
            messageContext.put(MessageContext.HTTP_RESPONSE_CODE, statusCode);

            Object servletResponse = messageContext.get(MessageContext.SERVLET_RESPONSE);
            if (servletResponse instanceof HttpServletResponse response) {
                response.setStatus(statusCode);
                try {
                    // CXF vervangt een ongecommitteerde null Provider-response door HTTP 202.
                    response.flushBuffer();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(
                        "Kan HTTP " + statusCode + "-response niet committen", e);
                }
            }
        }
    }
}
