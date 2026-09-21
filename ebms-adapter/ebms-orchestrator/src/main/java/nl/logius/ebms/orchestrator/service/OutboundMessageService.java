package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.EbxmlProfile;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Asynchrone AMQP-consument voor uitgaande ebMS2-berichten.
 *
 * <p>De {@code ebms_message}-boekhouding (aanmaken/bijwerken) verloopt volledig via
 * {@link OutboundMessageTrackingService}, in eigen direct-committende transacties
 * ({@code REQUIRES_NEW}) — losstaand van of signing/encryptie/verzenden hierna slaagt. Zo blijft
 * elke poging (geslaagd of mislukt) zichtbaar in {@code ebms_message}/de admin-UI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageService {

    // Foutcodes die niet hersteld kunnen worden door opnieuw te proberen (geen requeue)
    private static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
            "CHANNEL_NOT_FOUND",
            "INVALID_HEADER",
            "CPA_NOT_FOUND",
            "ROUTE_NOT_FOUND",
            "ROUTE_AMBIGUOUS",
            "CPA_ROUTE_INVALID",
            "CPA_ROLE_MISMATCH",
            // O-5: signing/encryptie-fouten (XmlSecurityException) zijn per definitie niet
            // herstelbaar door opnieuw te proberen (bv. onbekende key-alias) - zonder deze regel
            // requeue't zo'n permanente fout oneindig i.p.v. naar de DLQ te gaan.
            "SecurityFailure",
            // O-7: een ebXML ErrorList-afwijzing van de partner (zie OutboundSoapClient) is een
            // functionele weigering, geen transiënte netwerkfout - opnieuw aanbieden verandert
            // niets aan het resultaat.
            "PARTNER_REJECTED"
    );

    private final CpaChannelCacheService cpaChannelCacheService;
    private final CryptoServiceClient cryptoServiceClient;
    private final OutboundSoapClient outboundSoapClient;
    private final SoapHelper soapHelper;
    private final RabbitTemplate rabbitTemplate;
    private final OutboundMessageTrackingService trackingService;

    @Value("${ebms.outbound.signing-key-alias:signing-key}")
    private String defaultSigningKeyAlias;

    // ── AMQP Listener ─────────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitMqConfig.QUEUE_OUTBOUND)
    public void handleOutboundMessage(
            EbmsOutboundMessage message,
            Channel amqpChannel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        EbxmlMessageHeader header = message.getHeader();
        if (header == null || header.getCpaId() == null) {
            log.error("[OUTBOUND] Ongeldig bericht: ontbrekende header");
            nack(amqpChannel, deliveryTag, false); // Gooi weg / stuur naar DLQ
            return;
        }

        String messageId = resolveMessageId(message, header);
        if (messageId == null) {
            log.error("[OUTBOUND] Ongeldig bericht: messageId ontbreekt (noch top-level, noch header.messageInfo)");
            nack(amqpChannel, deliveryTag, false); // Gooi weg / stuur naar DLQ - kan niet gepersisteerd worden zonder id
            return;
        }
        log.info("[OUTBOUND] Verwerken: messageId={}", messageId);

        // ── Direct persisteren (eigen, altijd-committende transactie) zodat elke poging
        //    zichtbaar is in ebms_message/UI, ook als signing/encryptie/verzenden hierna faalt.
        trackingService.createOrUpdateProcessing(messageId, message, header, null, null);

        try {
            String cpaId = header.getCpaId();
            String fromPartyId = extractFromPartyId(header);
            String toPartyId = extractToPartyId(header);
            String service = extractService(header);
            String action = requireHeaderValue("Action", header.getAction());

            // ── 1. Volledige CPA-route ophalen en rollen toepassen ─────────
            OutboundRouteDto route = cpaChannelCacheService.getOutboundRoute(
                cpaId, fromPartyId, toPartyId, service, header.getService().getType(), action,
                header.getFromRole(), header.getToRole());
            applyCpaRoles(header, route);
            DeliveryChannelDto channel = route.getChannel();
            EbxmlProfile profile = EbxmlProfile.fromCode(channel.getDkProfile());
            boolean requireAck = profile.hasReliableMessaging();
            boolean awaitAsyncSignal = isAsyncReplyMode(channel);
            boolean requireSignature = route.isSignatureRequired() || profile.requiresSigning();

            // ── 2. SOAP-envelop opbouwen ──────────────────────────────────
            SOAPMessage soapMsg = soapHelper.buildOutboundSoap(header, requireAck);
            String rawSoapXml = soapHelper.soapToString(soapMsg);

            // ── 3. Signing (indien vereist door profiel) ───────────────────
            if (requireSignature) {
                String signingAlias = defaultSigningKeyAlias;
                log.info("[OUTBOUND] Signing: messageId={} alias={} hashFunction={} signatureAlgorithm={}",
                    messageId, signingAlias, route.getHashFunction(), route.getSignatureAlgorithm());
                rawSoapXml = cryptoServiceClient.sign(rawSoapXml, signingAlias, messageId,
                    route.getHashFunction(), route.getSignatureAlgorithm());
            }

            // ── 4. Encryptie (indien vereist door profiel) ─────────────────
            if (profile.requiresEncryption()) {
                String recipientAlias = toPartyId;
                log.debug("[OUTBOUND] Versleutelen: messageId={} recipient={}", messageId, recipientAlias);
                rawSoapXml = cryptoServiceClient.encrypt(rawSoapXml, recipientAlias, messageId);
            }

            // ── 5. Verrijk het gepersisteerde bericht met SOAP-envelop + kanaal ─
            trackingService.createOrUpdateProcessing(messageId, message, header, rawSoapXml, channel);

            // ── 6. Versturen via CXF SOAP-client ──────────────────────────
            outboundSoapClient.send(channel.getEndpointUrl(), rawSoapXml, cpaId, toPartyId);

            // ── 7. Status-machine bijwerken ────────────────────────────────
            trackingService.markSentOrDelivered(messageId, requireAck || awaitAsyncSignal);
            log.info("[OUTBOUND] Verzonden ({}): messageId={}",
                requireAck || awaitAsyncSignal ? "wacht op async MSH-signaal" : "BE – DELIVERED", messageId);

            // ── 8. Audit-event publiceren ──────────────────────────────────
            publishAudit(AuditEvent.builder()
                .eventType("MESSAGE_SENT")
                .messageId(messageId)
                .conversationId(header.getConversationId())
                .cpaId(cpaId)
                .action(header.getAction())
                .result("SUCCESS")
                .build());

            // ── 9. AMQP ACK ───────────────────────────────────────────────
            ack(amqpChannel, deliveryTag);

        } catch (EbmsException e) {
            log.error("[OUTBOUND] EbmsException: messageId={} code={} msg={}",
                messageId, e.getErrorCode(), e.getMessage());

            trackingService.markFailed(messageId,
                "[" + e.getErrorCode() + "] " + e.getMessage());

            boolean retryable = !NON_RETRYABLE_ERROR_CODES.contains(e.getErrorCode());
            if (retryable) {
                ack(amqpChannel, deliveryTag);
            } else {
                log.warn("[OUTBOUND] Niet-herstelbare fout voor messageId={}. Bericht wordt niet opnieuw aangeboden.", messageId);
                nack(amqpChannel, deliveryTag, false);
            }

        } catch (Exception e) {
            log.error("[OUTBOUND] Onverwachte fout: messageId={}", messageId, e);
            trackingService.markFailed(messageId,
                e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "<geen detail>"));
            ack(amqpChannel, deliveryTag);
        }
    }

    // ── Interne helpers ───────────────────────────────────────────────────────

    /**
     * Conform ebMS2 (ISO 15000-2) hoort de verzendende MSH (dus wij) de MessageId toe te kennen.
     * Valt terug op {@code header.messageInfo.messageId} als het top-level AMQP-veld ontbreekt,
     * zodat producenten het niet dubbel hoeven aan te leveren. {@code null} als beide ontbreken.
     */
    private String resolveMessageId(EbmsOutboundMessage message, EbxmlMessageHeader header) {
        String messageId = message.getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        if (header.getMessageInfo() != null
                && header.getMessageInfo().getMessageId() != null
                && !header.getMessageInfo().getMessageId().isBlank()) {
            return header.getMessageInfo().getMessageId();
        }
        return null;
    }

    private String extractToPartyId(EbxmlMessageHeader header) {
        if (header.getTo() != null && !header.getTo().isEmpty()) {
            return header.getTo().get(0).getValue();
        }
        throw new EbmsException("INVALID_HEADER", "To-partij ontbreekt in ebXML MessageHeader");
    }

    private String extractFromPartyId(EbxmlMessageHeader header) {
        if (header.getFrom() != null && !header.getFrom().isEmpty()) {
            return requireHeaderValue("From/PartyId", header.getFrom().get(0).getValue());
        }
        throw new EbmsException("INVALID_HEADER", "From-partij ontbreekt in ebXML MessageHeader");
    }

    private String extractService(EbxmlMessageHeader header) {
        if (header.getService() == null) {
            throw new EbmsException("INVALID_HEADER", "Service ontbreekt in ebXML MessageHeader");
        }
        return requireHeaderValue("Service", header.getService().getValue());
    }

    private String requireHeaderValue(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new EbmsException("INVALID_HEADER", name + " ontbreekt in ebXML MessageHeader");
        }
        return value;
    }

    private void applyCpaRoles(EbxmlMessageHeader header, OutboundRouteDto route) {
        header.setFromRole(resolveRole("From/Role", header.getFromRole(), route.getFromRole()));
        header.setToRole(resolveRole("To/Role", header.getToRole(), route.getToRole()));
    }

    private String resolveRole(String location, String suppliedRole, String cpaRole) {
        if (cpaRole == null || cpaRole.isBlank()) {
            throw new EbmsException("CPA_ROUTE_INVALID", location + " ontbreekt in de CPA-route");
        }
        if (suppliedRole != null && !suppliedRole.isBlank() && !suppliedRole.equals(cpaRole)) {
            throw new EbmsException("CPA_ROLE_MISMATCH",
                location + " komt niet exact overeen met de CPA: ontvangen='" + suppliedRole
                    + "', verwacht='" + cpaRole + "'");
        }
        return cpaRole;
    }

    private boolean isAsyncReplyMode(DeliveryChannelDto channel) {
        return channel != null && "none".equalsIgnoreCase(channel.getSyncReplyMode());
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
