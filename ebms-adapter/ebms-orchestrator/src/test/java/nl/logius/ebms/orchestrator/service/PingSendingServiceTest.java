package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.model.amqp.EbmsAsyncPongMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.PingEchoService;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PingSendingServiceTest {

    @Mock RabbitTemplate rabbitTemplate;
    @Mock CpaValidationService cpaValidationService;
    @Mock PingEchoService pingEchoService;
    @Mock SoapHelper soapHelper;
    @Mock OutboundSoapClient outboundSoapClient;
    @Mock Channel amqpChannel;

    @InjectMocks PingSendingService service;

    private static final String CPA_ID = "CPAID_EchoService-1-0-HTTPS";
    private static final String FROM_PARTY = "00000004003214345001";
    private static final String ENDPOINT = "https://sender.example.com/ebms";
    private EbxmlMessageHeader pingHeader;

    @BeforeEach
    void setUp() {
        pingHeader = EbxmlMessageHeader.builder()
            .cpaId(CPA_ID)
            .conversationId("conversation-1")
            .from(List.of(PartyId.builder().value(FROM_PARTY).build()))
            .to(List.of(PartyId.builder().value("00000002003214345001").build()))
            .messageInfo(MessageInfo.builder().messageId("ping-1").timestamp(Instant.now()).build())
            .build();
    }

    @Test
    void sendAsyncPong_publishesDurableTask() {
        service.sendAsyncPong(pingHeader);

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitMqConfig.EXCHANGE_EBMS),
            eq(RabbitMqConfig.ROUTING_ASYNC_PONG),
            any(EbmsAsyncPongMessage.class));
    }

    @Test
    void dispatchPong_usesOriginalSenderChannelAndSendsPong() throws Exception {
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .endpointUrl(ENDPOINT).build();
        SOAPMessage pong = mock(SOAPMessage.class);
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY)).thenReturn(channel);
        when(pingEchoService.handlePing(pingHeader)).thenReturn(pong);
        when(soapHelper.soapToString(pong)).thenReturn("<pong/>");

        service.dispatchPong(pingHeader);

        verify(cpaValidationService).getDeliveryChannel(CPA_ID, FROM_PARTY);
        verify(outboundSoapClient).send(ENDPOINT, "<pong/>", CPA_ID, FROM_PARTY);
    }

    @Test
    void handleAsyncPong_successAcknowledgesQueueMessage() throws Exception {
        DeliveryChannelDto channel = DeliveryChannelDto.builder().endpointUrl(ENDPOINT).build();
        SOAPMessage pong = mock(SOAPMessage.class);
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY)).thenReturn(channel);
        when(pingEchoService.handlePing(pingHeader)).thenReturn(pong);
        when(soapHelper.soapToString(pong)).thenReturn("<pong/>");

        service.handleAsyncPong(
            EbmsAsyncPongMessage.builder().pingHeader(pingHeader).build(), amqpChannel, 7L);

        verify(outboundSoapClient).send(ENDPOINT, "<pong/>", CPA_ID, FROM_PARTY);
        verify(amqpChannel).basicAck(7L, false);
    }

    @Test
    void handleAsyncPong_cpaFailureRequeuesQueueMessage() throws Exception {
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_PARTY))
            .thenThrow(new nl.logius.ebms.common.exception.EbmsException("CPA_SERVICE_UNAVAILABLE", "down"));

        service.handleAsyncPong(
            EbmsAsyncPongMessage.builder().pingHeader(pingHeader).build(), amqpChannel, 8L);

        verify(amqpChannel).basicNack(8L, false, true);
    }
}
