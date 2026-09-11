package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.EbmsAsyncAckMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.EbxmlProfile;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Set;

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
    private final CryptoServiceClient cryptoServiceClient;
    private final RabbitTemplate rabbitTemplate;

    private static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
        "CHANNEL_NOT_FOUND", "CERTIFICATE_NOT_FOUND", "CPA_NOT_FOUND", "PARTNER_REJECTED", "SecurityFailure");

    @Value("${ebms.outbound.signing-key-alias:signing-key}")
    private String signingKeyAlias;

    public void sendAsyncAck(EbxmlMessageHeader header, String cpaId, String fromPartyId) {
        boolean signed = header.getAckRequested() != null && header.getAckRequested().isSigned();
        rabbitTemplate.convertAndSend(
            RabbitMqConfig.EXCHANGE_EBMS,
            RabbitMqConfig.ROUTING_ASYNC_ACK,
            EbmsAsyncAckMessage.builder()
                .messageId(header.getMessageInfo().getMessageId())
                .cpaId(cpaId)
                .fromPartyId(fromPartyId)
                .ackRequestedSigned(signed)
                .build());
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_ASYNC_ACK)
    public void handleAsyncAck(EbmsAsyncAckMessage task, Channel amqpChannel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            dispatchAck(task);
            amqpChannel.basicAck(deliveryTag, false);
        } catch (EbmsException e) {
            log.error("[INBOUND] Async ACK mislukt: messageId={} code={} reden={}",
                task.getMessageId(), e.getErrorCode(), e.getMessage());
            nack(amqpChannel, deliveryTag, !NON_RETRYABLE_ERROR_CODES.contains(e.getErrorCode()));
        } catch (IllegalArgumentException e) {
            log.error("[INBOUND] Async ACK ongeldig: messageId={} reden={}",
                task.getMessageId(), e.getMessage());
            nack(amqpChannel, deliveryTag, false);
        } catch (Exception e) {
            log.error("[INBOUND] Technische fout bij async ACK: messageId={}", task.getMessageId(), e);
            nack(amqpChannel, deliveryTag, true);
        }
    }

    void dispatchAck(EbmsAsyncAckMessage task) {
        DeliveryChannelDto channel = cpaValidationService.getDeliveryChannel(task.getCpaId(), task.getFromPartyId());
        if (channel == null || channel.getEndpointUrl() == null || channel.getEndpointUrl().isBlank()) {
            throw new IllegalArgumentException("Geen endpoint voor async ACK: CPA=" + task.getCpaId()
                + " party=" + task.getFromPartyId());
        }

        EbxmlMessageHeader header = EbxmlMessageHeader.builder()
            .cpaId(task.getCpaId())
            .messageInfo(nl.logius.ebms.common.model.ebxml.MessageInfo.builder()
                .messageId(task.getMessageId()).build())
            .ackRequested(nl.logius.ebms.common.model.ebxml.AckRequested.builder()
                .signed(task.isAckRequestedSigned()).build())
            .build();
        SOAPMessage ack = soapHelper.createAck(header);
        String ackXml = soapHelper.soapToString(ack);
        boolean signedByProfile = channel.getDkProfile() != null
            && EbxmlProfile.fromCode(channel.getDkProfile()).requiresSigning();
        if (task.isAckRequestedSigned() || signedByProfile) {
            ackXml = cryptoServiceClient.sign(ackXml, signingKeyAlias, task.getMessageId());
        }
        outboundSoapClient.send(channel.getEndpointUrl(), ackXml, task.getCpaId(), task.getFromPartyId());
        log.info("[INBOUND] Asynchrone ACK verstuurd: messageId={} naar endpoint={}",
            task.getMessageId(), channel.getEndpointUrl());
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception e) {
            log.warn("[INBOUND] Async ACK NACK mislukt: {}", e.getMessage());
        }
    }
}
