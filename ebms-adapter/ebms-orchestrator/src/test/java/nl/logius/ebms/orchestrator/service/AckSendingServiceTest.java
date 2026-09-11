package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import com.rabbitmq.client.Channel;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.amqp.EbmsAsyncAckMessage;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
    @Mock CryptoServiceClient cryptoServiceClient;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock Channel amqpChannel;

    @InjectMocks AckSendingService service;

    private EbxmlMessageHeader header;
    private static final String CPA_ID = "urn:test:cpa:async";
    private static final String FROM_PARTY_ID = "00000000000000000001";
    private static final String MESSAGE_ID = "msg-async-1";
    private static final String ENDPOINT_URL = "https://sender.example.com/ebms";

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "signingKeyAlias", "signing-key");
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

        when(soapHelper.createAck(any())).thenReturn(ack);
        when(soapHelper.soapToString(ack)).thenReturn("<ack/>");
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID)).thenReturn(channel);

        service.dispatchAck(task(false));

        verify(soapHelper).createAck(any());
        // REVERSE lookup: fromPartyId (afzender van origineel), niet toPartyId
        verify(cpaValidationService).getDeliveryChannel(CPA_ID, FROM_PARTY_ID);
        verify(outboundSoapClient).send(eq(ENDPOINT_URL), eq("<ack/>"), eq(CPA_ID), eq(FROM_PARTY_ID));
    }

    @Test
    @DisplayName("sendAsyncAck: signed=true -> ACK wordt gesigneerd vóór verzending")
    void sendAsyncAck_signedRequested_signsBeforeSend() {
        header.setAckRequested(nl.logius.ebms.common.model.ebxml.AckRequested.builder()
            .signed(true).build());
        SOAPMessage ack = mock(SOAPMessage.class);
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .endpointUrl(ENDPOINT_URL).dkProfile("osb-rm").build();

        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID)).thenReturn(channel);
        when(soapHelper.createAck(any())).thenReturn(ack);
        when(soapHelper.soapToString(ack)).thenReturn("<unsigned-ack/>");
        when(cryptoServiceClient.sign("<unsigned-ack/>", "signing-key", MESSAGE_ID))
            .thenReturn("<signed-ack/>");

        service.dispatchAck(task(true));

        verify(cryptoServiceClient).sign("<unsigned-ack/>", "signing-key", MESSAGE_ID);
        verify(outboundSoapClient).send(eq(ENDPOINT_URL), eq("<signed-ack/>"), eq(CPA_ID), eq(FROM_PARTY_ID));
    }

    @Test
    @DisplayName("sendAsyncAck: ontbrekend endpoint -> geen outbound call")
    void sendAsyncAck_missingEndpoint_doesNotSend() {
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID))
            .thenReturn(DeliveryChannelDto.builder().dkProfile("osb-rm").build());

        service.dispatchAck(task(false));

        verify(soapHelper, never()).createAck(any());
        verify(outboundSoapClient, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("trigger: async ACK-taak wordt durable op RabbitMQ gepubliceerd")
    void sendAsyncAck_publishesTask() {
        service.sendAsyncAck(header, CPA_ID, FROM_PARTY_ID);

        verify(rabbitTemplate).convertAndSend(
            eq(nl.logius.ebms.orchestrator.config.RabbitMqConfig.EXCHANGE_EBMS),
            eq(nl.logius.ebms.orchestrator.config.RabbitMqConfig.ROUTING_ASYNC_ACK),
            any(EbmsAsyncAckMessage.class));
    }

    @Test
    @DisplayName("consumer: succesvolle async ACK wordt geacknowledged")
    void handleAsyncAck_success_acknowledgesMessage() throws Exception {
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID))
            .thenReturn(DeliveryChannelDto.builder().endpointUrl(ENDPOINT_URL).build());
        when(soapHelper.createAck(any())).thenReturn(mock(SOAPMessage.class));
        when(soapHelper.soapToString(any())).thenReturn("<ack/>");

        service.handleAsyncAck(task(false), amqpChannel, 7L);

        verify(outboundSoapClient).send(eq(ENDPOINT_URL), eq("<ack/>"), eq(CPA_ID), eq(FROM_PARTY_ID));
        verify(amqpChannel).basicAck(7L, false);
    }

    @Test
    @DisplayName("consumer: tijdelijke fout wordt gerequeued")
    void handleAsyncAck_transientFailure_requeuesMessage() throws Exception {
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY_ID))
            .thenThrow(new nl.logius.ebms.common.exception.EbmsException("CPA_SERVICE_UNAVAILABLE", "down"));

        service.handleAsyncAck(task(false), amqpChannel, 8L);

        verify(amqpChannel).basicNack(8L, false, true);
    }

    private EbmsAsyncAckMessage task(boolean signed) {
        return EbmsAsyncAckMessage.builder()
            .messageId(MESSAGE_ID).cpaId(CPA_ID).fromPartyId(FROM_PARTY_ID)
            .ackRequestedSigned(signed).build();
    }

}
