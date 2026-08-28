package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.EbxmlProfile;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageService {

    private final EbmsMessageRepository messageRepository;
    private final CpaChannelCacheService cpaChannelCacheService;
    private final CryptoServiceClient cryptoServiceClient;
    private final OutboundSoapClient outboundSoapClient;
    private final SoapHelper soapHelper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${ebms.outbound.signing-key-alias:signing-key}")
    private String defaultSigningKeyAlias;

    @RabbitListener(queues = RabbitMqConfig.QUEUE_OUTBOUND)
    @Transactional
    public void handleOutboundMessage(
            EbmsOutboundMessage message,
            Channel amqpChannel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        String messageId = message.getMessageId();
        log.info("[OUTBOUND] Verwerken: messageId={}", messageId);

        try {
            EbxmlMessageHeader header = message.getHeader();
            if (header == null || header.getCpaId() == null) {
                log.error("[OUTBOUND] Ongeldig bericht: messageId={} – ontbrekende header", messageId);
                nack(amqpChannel, deliveryTag, false);
                return;
            }

            String cpaId = header.getCpaId();
            String toPartyId = extractToPartyId(header);

            DeliveryChannelDto channel = cpaChannelCacheService.getChannel(cpaId, toPartyId);
            EbxmlProfile profile = EbxmlProfile.fromCode(channel.getDkProfile());
            boolean requireAck = profile.hasReliableMessaging();

            SOAPMessage soapMsg = soapHelper.buildOutboundSoap(header, requireAck);
            String rawSoapXml = soapHelper.soapToString(soapMsg);

            if (profile.requiresSigning()) {
                String signingAlias = defaultSigningKeyAlias;
                log.debug("[OUTBOUND] Signing: messageId={} alias={}", messageId, signingAlias);
                rawSoapXml = cryptoServiceClient.sign(rawSoapXml, signingAlias, messageId);
            }

            if (profile.requiresEncryption()) {
                String recipientAlias = toPartyId;
                log.debug("[OUTBOUND] Versleutelen: messageId={} recipient={}", messageId, recipientAlias);
                rawSoapXml = cryptoServiceClient.encrypt(rawSoapXml, recipientAlias, messageId);
            }

            EbmsMessageEntity entity = persistOutboundMessage(message, header, rawSoapXml, channel);

            outboundSoapClient.send(channel.getEndpointUrl(), rawSoapXml, cpaId, toPartyId);

            if (requireAck) {
                entity.setStatus(MessageStatus.SENT);
                entity.setAckRequested(true);
                log.info("[OUTBOUND] Verzonden (RM) – wacht op ACK: messageId={}", messageId);
            } else {
                entity.setStatus(MessageStatus.DELIVERED);
                log.info("[OUTBOUND] Verzonden (BE) – DELIVERED: messageId={}", messageId);
            }
            messageRepository.save(entity);

            publishAudit(AuditEvent.builder()
                .eventType("MESSAGE_SENT")
                .messageId(messageId)
                .conversationId(header.getConversationId())
                .cpaId(cpaId)
                .action(header.getAction())
                .result("SUCCESS")
                .build());

            ack(amqpChannel, deliveryTag);

        } catch (EbmsException e) {
            log.error("[OUTBOUND] EbmsException: messageId={} code={} msg={}",
                messageId, e.getErrorCode(), e.getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            nack(amqpChannel, deliveryTag, true);

        } catch (Exception e) {
            log.error("[OUTBOUND] Onverwachte fout: messageId={}", messageId, e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            nack(amqpChannel, deliveryTag, true);
        }
    }

    private EbmsMessageEntity persistOutboundMessage(
            EbmsOutboundMessage message,
            EbxmlMessageHeader header,
            String rawSoapXml,
            DeliveryChannelDto channel) {

        String fromPartyId = header.getFrom() != null && !header.getFrom().isEmpty()
            ? header.getFrom().get(0).getValue() : "UNKNOWN";
        String toPartyId = header.getTo() != null && !header.getTo().isEmpty()
            ? header.getTo().get(0).getValue() : "UNKNOWN";
        String messageId = message.getMessageId();

        return messageRepository.findByMessageId(messageId)
            .map(existing -> {
                existing.setRawSoapXml(rawSoapXml);
                existing.setPayloadRef(message.getPayloadRef());
                existing.setPayloadContentType(message.getPayloadContentType());
                existing.setStatus(MessageStatus.PROCESSING);
                log.debug("[OUTBOUND] Idempotente herverwerking: messageId={}", messageId);
                return messageRepository.save(existing);
            })
            .orElseGet(() -> {
                Instant ttl = channel.getPersistDuration() != null
                    ? Instant.now().plusSeconds(channel.getPersistDuration()) : null;

                EbmsMessageEntity entity = EbmsMessageEntity.builder()
                    .messageId(messageId)
                    .conversationId(header.getConversationId())
                    .cpaId(header.getCpaId())
                    .fromPartyId(fromPartyId)
                    .toPartyId(toPartyId)
                    .fromRole(header.getFromRole())
                    .toRole(header.getToRole())
                    .service(header.getService() != null ? header.getService().getValue() : "UNKNOWN")
                    .serviceType(header.getService() != null ? header.getService().getType() : null)
                    .action(header.getAction())
                    .direction(MessageDirection.OUTBOUND)
                    .status(MessageStatus.PROCESSING)
                    .timestamp(Instant.now())
                    .timeToLive(ttl)
                    .payloadRef(message.getPayloadRef())
                    .payloadContentType(message.getPayloadContentType())
                    .rawSoapXml(rawSoapXml)
                    .build();

                return messageRepository.save(entity);
            });
    }

    private String extractToPartyId(EbxmlMessageHeader header) {
        if (header.getTo() != null && !header.getTo().isEmpty()) {
            return header.getTo().get(0).getValue();
        }
        throw new EbmsException("INVALID_HEADER", "To-partij ontbreekt in ebXML MessageHeader");
    }

    private void publishAudit(AuditEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_EBMS,
                RabbitMqConfig.ROUTING_AUDIT,
                event);
        } catch (Exception e) {
            log.warn("[OUTBOUND] Audit-event kon niet gepubliceerd worden: {}", e.getMessage());
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("[OUTBOUND] AMQP ACK mislukt: {}", e.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception e) {
            log.warn("[OUTBOUND] AMQP NACK mislukt: {}", e.getMessage());
        }
    }
}
