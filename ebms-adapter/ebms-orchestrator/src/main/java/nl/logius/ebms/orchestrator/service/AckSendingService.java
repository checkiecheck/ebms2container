package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Verstuurt een ebXML Acknowledgment als losse, asynchrone uitgaande SOAP-call naar de
 * oorspronkelijke verzender - gebruikt wanneer de CPA {@code syncReplyMode="none"} voorschrijft
 * (Digikoppeling-default conform Koppelvlakstandaard ebMS2 v3.3+, SyncReplyModule).
 *
 * <p>Fire-and-forget by design: mislukt de verzending, dan triggert de partner zelf een
 * Reliable Messaging-retry van het originele bericht (wat opnieuw een async-ACK-poging
 * veroorzaakt bij {@link OrchestratorService#processInboundMessage} - zie de duplicaat-tak
 * daar) - er is dus geen aparte retry-/statusadministratie voor de ACK zelf nodig.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AckSendingService {

    private final SoapHelper soapHelper;
    private final OutboundSoapClient outboundSoapClient;
    private final CpaValidationService cpaValidationService;

    @Async("ackTaskExecutor")
    public void sendAsyncAck(EbxmlMessageHeader header, String cpaId, String fromPartyId) {
        String messageId = header.getMessageInfo().getMessageId();
        try {
            SOAPMessage ack = soapHelper.createAck(header);
            DeliveryChannelDto channel = cpaValidationService.getDeliveryChannel(cpaId, fromPartyId);
            outboundSoapClient.send(channel.getEndpointUrl(), soapHelper.soapToString(ack), cpaId, fromPartyId);
            log.info("[INBOUND] Asynchrone ACK verstuurd: messageId={} naar endpoint={}",
                messageId, channel.getEndpointUrl());
        } catch (Exception e) {
            log.error("[INBOUND] Asynchrone ACK-verzending mislukt (partner-RM-retry triggert "
                + "later een nieuwe poging): messageId={} reden={}", messageId, e.getMessage());
        }
    }
}
