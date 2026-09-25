package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.EbmsAsyncPongMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.PingEchoService;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Publishes and dispatches asynchronous Pong responses for CPA async Ping flows. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PingSendingService {

    private final RabbitTemplate rabbitTemplate;
    private final CpaValidationService cpaValidationService;
    private final PingEchoService pingEchoService;
    private final SoapHelper soapHelper;
    private final OutboundSoapClient outboundSoapClient;

    private static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
        "CHANNEL_NOT_FOUND", "CERTIFICATE_NOT_FOUND", "CPA_NOT_FOUND", "PARTNER_REJECTED", "SecurityFailure");

    public void sendAsyncPong(EbxmlMessageHeader pingHeader) {
        rabbitTemplate.convertAndSend(
            RabbitMqConfig.EXCHANGE_EBMS,
            RabbitMqConfig.ROUTING_ASYNC_PONG,
            EbmsAsyncPongMessage.builder().pingHeader(pingHeader).build());
        log.info("[PING] Asynchrone Pong-taak gepubliceerd: messageId={} cpaId={}",
            pingHeader.getMessageInfo().getMessageId(), pingHeader.getCpaId());
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_ASYNC_PONG)
    public void handleAsyncPong(EbmsAsyncPongMessage task, Channel amqpChannel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            dispatchPong(task.getPingHeader());
            amqpChannel.basicAck(deliveryTag, false);
        } catch (EbmsException e) {
            log.error("[PING] Async Pong mislukt: messageId={} code={} reden={}",
                messageId(task), e.getErrorCode(), e.getMessage());
            nack(amqpChannel, deliveryTag, !NON_RETRYABLE_ERROR_CODES.contains(e.getErrorCode()));
        } catch (Exception e) {
            log.error("[PING] Technische fout bij async Pong: messageId={}", messageId(task), e);
            nack(amqpChannel, deliveryTag, true);
        }
    }

    void dispatchPong(EbxmlMessageHeader pingHeader) {
        String fromPartyId = pingHeader.getFrom().isEmpty() ? null : pingHeader.getFrom().get(0).getValue();
        DeliveryChannelDto channel = cpaValidationService.getDeliveryChannel(pingHeader.getCpaId(), fromPartyId);
        if (channel == null || channel.getEndpointUrl() == null || channel.getEndpointUrl().isBlank()) {
            throw new EbmsException("CHANNEL_NOT_FOUND",
                "Geen endpoint voor async Pong: CPA=" + pingHeader.getCpaId() + " party=" + fromPartyId);
        }

        SOAPMessage pong = pingEchoService.handlePing(pingHeader);
        outboundSoapClient.send(
            channel.getEndpointUrl(),
            soapHelper.soapToString(pong),
            pingHeader.getCpaId(),
            fromPartyId);
        log.info("[PING] Asynchrone Pong verstuurd: messageId={} naar endpoint={}",
            messageId(pingHeader), channel.getEndpointUrl());
    }

    private String messageId(EbmsAsyncPongMessage task) {
        return task != null && task.getPingHeader() != null ? messageId(task.getPingHeader()) : null;
    }

    private String messageId(EbxmlMessageHeader header) {
        return header != null && header.getMessageInfo() != null
            ? header.getMessageInfo().getMessageId() : null;
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception e) {
            log.warn("[PING] NACK async Pong mislukt: {}", e.getMessage());
        }
    }
}
