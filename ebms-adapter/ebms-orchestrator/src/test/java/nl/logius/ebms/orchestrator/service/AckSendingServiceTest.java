package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests voor {@link AckSendingService}.
 *
 * <p>Iteration_30: Reliable Messaging - Async ACKs. Verifieert dat sendAsyncAck:
 * <ul>
 *   <li>De ACK bouwt via SoapHelper.createAck(header)</li>
 *   <li>Het REVERSE afleverkanaal opzoekt (cpaId + fromPartyId, de zender van het originele bericht)</li>
 *   <li>Verzendt via outboundSoapClient.send(endpointUrl, ackXml, cpaId, fromPartyId)</li>
 *   <li>Fail-safe is: exceptions worden gevangen en gelogd, niet doorgeworpen</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AckSendingServiceTest {

    @Mock SoapHelper soapHelper;
    @Mock OutboundSoapClient outboundSoapClient;
    @Mock CpaValidationService cpaValidationService;

    @InjectMocks AckSendingService service;

    private EbxmlMessageHeader header;
    private static final String CPA_ID = "urn:test:cpa:async";
    private static final String FROM_PARTY_ID = "00000000000000000001";
    private static final String MESSAGE_ID = "msg-async-1";
    private static final String ENDPOINT_URL = "https://sender.example.com/ebms";

    @BeforeEach
    void setUp() {
        header = EbxmlMessageHeader.builder()
            .cpaId(CPA_ID)
            .conversationId("conv-1")
            .from(List.of(PartyId.builder().value(FROM_PARTY_ID).build()))
            .messageInfo(MessageInfo.builder().messageId(MESSAGE_ID).timestamp(Instant.now()).build())
            .build();
    }

    @Test
    @DisplayName("sendAsyncAck: bouwt ACK, doet reverse lookup (fromPartyId), verstuurt via OutboundSoapClient")
    void sendAsyncAck_happyPath_buildsAckLooksUpSenderAndSends() {
        SOAPMessage ack = mock(SOAPMessage.class);
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .cpaId(CPA_ID).partyId(FROM_PARTY_ID).endpointUrl(ENDPOINT_URL)
            .syncReplyMode("none").build();

        when(soapHelper.createAck(header)).thenReturn(ack);
        when(soapHelper.soapToString(ack)).thenReturn("<ack/>");
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID)).thenReturn(channel);

        service.sendAsyncAck(header, CPA_ID, FROM_PARTY_ID);

        verify(soapHelper).createAck(header);
        // REVERSE lookup: fromPartyId (afzender van origineel), niet toPartyId
        verify(cpaValidationService).getDeliveryChannel(CPA_ID, FROM_PARTY_ID);
        verify(outboundSoapClient).send(eq(ENDPOINT_URL), eq("<ack/>"), eq(CPA_ID), eq(FROM_PARTY_ID));
    }

    @Test
    @DisplayName("sendAsyncAck: channel lookup mislukt -> exception opgeslokt, geen send, geen re-throw")
    void sendAsyncAck_channelNotFound_swallowsExceptionNoSend() {
        SOAPMessage ack = mock(SOAPMessage.class);
        when(soapHelper.createAck(header)).thenReturn(ack);
        when(cpaValidationService.getDeliveryChannel(anyString(), anyString()))
            .thenThrow(new RuntimeException("CPA-channel niet gevonden"));

        // Mag geen exception doorgeven (fire-and-forget)
        service.sendAsyncAck(header, CPA_ID, FROM_PARTY_ID);

        verify(outboundSoapClient, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendAsyncAck: outboundSoapClient.send() gooit -> exception opgeslokt, geen re-throw")
    void sendAsyncAck_sendThrows_swallowsExceptionNoRethrow() {
        SOAPMessage ack = mock(SOAPMessage.class);
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .endpointUrl(ENDPOINT_URL).build();

        when(soapHelper.createAck(header)).thenReturn(ack);
        when(soapHelper.soapToString(ack)).thenReturn("<ack/>");
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID)).thenReturn(channel);
        when(outboundSoapClient.send(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("HTTP-verbinding mislukt"));

        // Mag geen exception doorgeven
        service.sendAsyncAck(header, CPA_ID, FROM_PARTY_ID);

        verify(outboundSoapClient).send(eq(ENDPOINT_URL), eq("<ack/>"), eq(CPA_ID), eq(FROM_PARTY_ID));
    }
}
