package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.dto.ConnectivityPingResult;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConnectivityPingService {

    private final CpaValidationService cpaValidationService;
    private final OutboundSoapClient outboundSoapClient;
    private final SoapHelper soapHelper;

    public ConnectivityPingResult ping(String cpaId, String fromPartyId, String toPartyId) {
        long startedAt = System.nanoTime();
        try {
            DeliveryChannelDto channel = cpaValidationService.getDeliveryChannel(cpaId, toPartyId);
            String messageId = UUID.randomUUID() + "@ebms-orchestrator";
            EbxmlMessageHeader pingHeader = EbxmlMessageHeader.builder()
                .cpaId(cpaId)
                .conversationId(UUID.randomUUID().toString())
                .from(List.of(PartyId.builder().value(fromPartyId).build()))
                .to(List.of(PartyId.builder().value(toPartyId).build()))
                .service(ServiceType.builder().value(SoapHelper.EBXML_PING_SERVICE).build())
                .action("Ping")
                .messageInfo(MessageInfo.builder()
                    .messageId(messageId)
                    .timestamp(Instant.now())
                    .build())
                .build();

            SOAPMessage ping = soapHelper.buildOutboundSoap(pingHeader, false);
            SOAPMessage response = outboundSoapClient.send(
                channel.getEndpointUrl(), soapHelper.soapToString(ping), cpaId, toPartyId);
            validatePong(response, messageId);
            return ConnectivityPingResult.success(elapsedMillis(startedAt));
        } catch (EbmsException e) {
            return ConnectivityPingResult.failure(elapsedMillis(startedAt), e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            return ConnectivityPingResult.failure(
                elapsedMillis(startedAt), describeFailure(e), e.getClass().getSimpleName());
        }
    }

    private void validatePong(SOAPMessage response, String pingMessageId) throws Exception {
        if (response == null || response.getSOAPHeader() == null) {
            throw new EbmsException("INVALID_PONG", "Partner gaf geen ebMS Pong-response");
        }
        EbxmlMessageHeader pong = soapHelper.parseMessageHeader(response.getSOAPHeader());
        if (pong == null
                || pong.getService() == null
                || !SoapHelper.EBXML_PING_SERVICE.equals(pong.getService().getValue())
                || !"Pong".equalsIgnoreCase(pong.getAction())) {
            throw new EbmsException("INVALID_PONG", "Partner-response is geen geldige ebMS Pong");
        }
        String refToMessageId = pong.getMessageInfo() != null
            ? pong.getMessageInfo().getRefToMessageId() : null;
        if (!pingMessageId.equals(refToMessageId)) {
            throw new EbmsException("INVALID_PONG", "Pong RefToMessageId verwijst niet naar de Ping");
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String describeFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }
}