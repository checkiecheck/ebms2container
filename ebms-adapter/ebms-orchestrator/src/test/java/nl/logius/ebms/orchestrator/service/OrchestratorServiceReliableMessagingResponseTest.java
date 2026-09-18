package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.common.model.ebxml.AckRequested;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.util.Optional;

/**
 * Mockito-only unit tests voor de nieuwe iteration_30 Reliable Messaging-response-tak in
 * {@link OrchestratorService#processInboundMessage} (via {@code buildInboundResponse}).
 *
 * <p>Contract:
 * <ul>
 *   <li>AckRequested + syncReplyMode="none"                        -> leeg antwoord + async ACK-trigger</li>
 *   <li>AckRequested + syncReplyMode="mshSignalsOnly"              -> embedded sync ACK (regressie)</li>
 *   <li>AckRequested + syncReplyMode=null (bestaande CPAs)         -> embedded sync ACK (regressie)</li>
 *   <li>AckRequested + getDeliveryChannel gooit (fail-safe)        -> embedded sync ACK (regressie)</li>
 *   <li>Geen AckRequested                                          -> leeg antwoord, geen async ACK</li>
 *   <li>Duplicate messageId + async mode                           -> leeg antwoord + async ACK-trigger (Gap 1)</li>
 *   <li>Duplicate messageId + sync mode                            -> embedded sync ACK (Gap 1)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorServiceReliableMessagingResponseTest {

    @Mock EbmsMessageRepository messageRepository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock SoapHelper soapHelper;
    @Mock CpaValidationService cpaValidationService;
    @Mock CryptoServiceClient cryptoServiceClient;
    @Mock nl.logius.ebms.orchestrator.config.RetryProperties retryProperties;
    @Mock InboundMessageTrackingService trackingService;
    @Mock AckSendingService ackSendingService;

    @InjectMocks OrchestratorService service;

    private SOAPMessage request;
    private SOAPMessage embeddedAck;
    private SOAPMessage signedAck;
    private SOAPMessage emptyResponse;

    private static final String MESSAGE_ID = "msg-rm-1";
    private static final String CPA_ID = "urn:test:cpa:rm";
    private static final String FROM_OIN = "00000000000000000001";
    private static final String TO_OIN   = "00000000000000000002";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enforceInboundOinValidation", true);
        ReflectionTestUtils.setField(service, "decryptionKeyAlias", "encryption-key");
        ReflectionTestUtils.setField(service, "signingKeyAlias", "signing-key");

        request = mock(SOAPMessage.class);
        embeddedAck = mock(SOAPMessage.class);
        signedAck = mock(SOAPMessage.class);
        emptyResponse = mock(SOAPMessage.class);

        when(cpaValidationService.validateCpaAndOin(anyString(), nullable(String.class)))
            .thenReturn(CpaValidationResult.success(null));
        when(soapHelper.hasEncryptedBody(any())).thenReturn(false);
        when(soapHelper.hasSignature(any())).thenReturn(false);
        when(messageRepository.existsByMessageId(anyString())).thenReturn(false);
        when(soapHelper.createEmptyResponse()).thenReturn(emptyResponse);
        when(soapHelper.createAck(any())).thenReturn(embeddedAck);
    }

    private EbxmlMessageHeader header(boolean ackRequested) {
        return header(ackRequested, false);
    }

    private EbxmlMessageHeader header(boolean ackRequested, boolean signed) {
        return EbxmlMessageHeader.builder()
            .cpaId(CPA_ID).conversationId("conv-1")
            .from(List.of(PartyId.builder().value(FROM_OIN).type("URN:OIN").build()))
            .to(List.of(PartyId.builder().value(TO_OIN).type("URN:OIN").build()))
            .fromRole("Sender").toRole("Receiver")
            .service(ServiceType.builder().value("urn:t").type("urn:t").build())
            .action("send")
            .messageInfo(MessageInfo.builder().messageId(MESSAGE_ID).timestamp(Instant.now()).build())
            .ackRequested(ackRequested
                ? AckRequested.builder().signed(signed).build()
                : null)
            .build();
    }

    private void mockChannelSyncReplyMode(String syncReplyMode) {
        DeliveryChannelDto ch = DeliveryChannelDto.builder()
            .cpaId(CPA_ID).partyId(FROM_OIN).endpointUrl("https://x/")
            .syncReplyMode(syncReplyMode).build();
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_OIN)).thenReturn(ch);
    }

    @Test
    @DisplayName("AckRequested + syncReplyMode=none -> lege response + async ACK getriggerd")
    void asyncMode_ackRequested_emptyResponseAndAsyncAckTriggered() {
        EbxmlMessageHeader h = header(true);
        mockChannelSyncReplyMode("none");

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(emptyResponse);
        verify(ackSendingService).sendAsyncAck(h, CPA_ID, FROM_OIN);
        verify(soapHelper, never()).createAck(any());
    }

    @Test
    @DisplayName("AckRequested + syncReplyMode=mshSignalsOnly -> embedded sync ACK (regressie)")
    void syncMode_ackRequested_embeddedAck() {
        EbxmlMessageHeader h = header(true);
        mockChannelSyncReplyMode("mshSignalsOnly");

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(embeddedAck);
        verify(soapHelper).createAck(h);
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
    }

    @Test
    @DisplayName("Nieuwe sync ACK met signed=true -> crypto-service sign en signed SOAP-response")
    void syncMode_signedAck_signsResponse() {
        EbxmlMessageHeader h = header(true, true);
        mockChannelSyncReplyMode("mshSignalsOnly");
        when(soapHelper.soapToString(embeddedAck)).thenReturn("<unsigned-ack/>");
        when(cryptoServiceClient.sign("<unsigned-ack/>", "signing-key", MESSAGE_ID))
            .thenReturn("<signed-ack/>");
        when(soapHelper.soapFromString("<signed-ack/>")).thenReturn(signedAck);

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(signedAck);
        verify(cryptoServiceClient).sign("<unsigned-ack/>", "signing-key", MESSAGE_ID);
        verify(soapHelper).soapFromString("<signed-ack/>");
    }

    @Test
    @DisplayName("AckRequested + syncReplyMode=null (bestaande CPAs) -> embedded sync ACK (regressie)")
    void syncMode_nullSyncReplyMode_embeddedAck() {
        EbxmlMessageHeader h = header(true);
        mockChannelSyncReplyMode(null);

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(embeddedAck);
        verify(soapHelper).createAck(h);
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
    }

    @Test
    @DisplayName("AckRequested + channel lookup gooit -> fail-safe: embedded sync ACK")
    void syncMode_channelLookupThrows_failSafeToSync() {
        EbxmlMessageHeader h = header(true);
        when(cpaValidationService.getDeliveryChannel(CPA_ID, FROM_OIN))
            .thenThrow(new RuntimeException("channel niet gevonden"));

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(embeddedAck);
        verify(soapHelper).createAck(h);
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
    }

    @Test
    @DisplayName("Geen AckRequested -> lege response, geen ACK (sync noch async)")
    void noAckRequested_emptyResponseNoAckAtAll() {
        EbxmlMessageHeader h = header(false);

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(emptyResponse);
        verify(soapHelper, never()).createAck(any());
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
    }

    @Test
    @DisplayName("MessageError system signal -> PROCESSED, niet DELIVERED")
    void messageError_isMarkedProcessedInsteadOfDelivered() {
        EbxmlMessageHeader h = header(false);
        h.setService(ServiceType.builder().value(SoapHelper.EBXML_PING_SERVICE).build());
        h.setAction("MessageError");

        SOAPMessage response = service.processInboundMessage(request, h, "<raw-error/>", FROM_OIN);

        assertThat(response).isSameAs(emptyResponse);
        verify(trackingService).persistReceived(h, "<raw-error/>", FROM_OIN);
        verify(trackingService).markProcessed(MESSAGE_ID);
        verify(trackingService, never()).markDelivered(MESSAGE_ID);
    }

    // ── Gap 1: Duplicate suppression met cached-response reuse ─────────────

    @Test
    @DisplayName("Duplicate + async mode -> leeg antwoord + nieuwe async ACK-trigger (Gap 1)")
    void duplicate_asyncMode_returnsEmptyAndTriggersAsyncAck() {
        EbxmlMessageHeader h = header(true);
        when(messageRepository.existsByMessageId(MESSAGE_ID)).thenReturn(true);
        mockChannelSyncReplyMode("none");

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(emptyResponse);
        // Duplicate-audit + tracking nog steeds aangeroepen
        verify(trackingService).recordDuplicate(MESSAGE_ID);
        // Nieuwe async ACK-trigger (i.p.v. DuplicateElimination-fault)
        verify(ackSendingService).sendAsyncAck(h, CPA_ID, FROM_OIN);
        // Bericht niet opnieuw gepersisteerd
        verify(trackingService, never()).persistReceived(any(), any(), any());
    }

    @Test
    @DisplayName("Duplicate + sync mode -> regenerated embedded ACK (Gap 1)")
    void duplicate_syncMode_returnsRegeneratedEmbeddedAck() {
        EbxmlMessageHeader h = header(true);
        when(messageRepository.existsByMessageId(MESSAGE_ID)).thenReturn(true);
        mockChannelSyncReplyMode("mshSignalsOnly");

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(embeddedAck);
        verify(trackingService).recordDuplicate(MESSAGE_ID);
        verify(soapHelper).createAck(h);
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
        verify(trackingService, never()).persistReceived(any(), any(), any());
    }

    @Test
    @DisplayName("Duplicate + signed=true -> opnieuw signeren en geen DuplicateElimination")
    void duplicate_syncMode_signedAck_signsRegeneratedResponse() {
        EbxmlMessageHeader h = header(true, true);
        when(messageRepository.existsByMessageId(MESSAGE_ID)).thenReturn(true);
        mockChannelSyncReplyMode("mshSignalsOnly");
        when(soapHelper.soapToString(embeddedAck)).thenReturn("<unsigned-ack/>");
        when(cryptoServiceClient.sign("<unsigned-ack/>", "signing-key", MESSAGE_ID))
            .thenReturn("<signed-ack/>");
        when(soapHelper.soapFromString("<signed-ack/>")).thenReturn(signedAck);

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(signedAck);
        verify(trackingService).recordDuplicate(MESSAGE_ID);
        verify(cryptoServiceClient).sign("<unsigned-ack/>", "signing-key", MESSAGE_ID);
        verify(soapHelper, never()).createErrorResponse(anyString(), anyString(), any());
        verify(trackingService, never()).persistReceived(any(), any(), any());
    }

    @Test
    @DisplayName("Duplicate zonder AckRequested -> lege response, geen async ACK")
    void duplicate_noAckRequested_returnsEmptyNoAckTrigger() {
        EbxmlMessageHeader h = header(false);
        when(messageRepository.existsByMessageId(MESSAGE_ID)).thenReturn(true);

        SOAPMessage response = service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        assertThat(response).isSameAs(emptyResponse);
        verify(trackingService).recordDuplicate(MESSAGE_ID);
        verify(soapHelper, never()).createAck(any());
        verify(ackSendingService, never()).sendAsyncAck(any(), any(), any());
    }

    @Test
    @DisplayName("Duplicate -> AMQP inbound queue NIET opnieuw gepubliceerd (geen dubbele bezorging)")
    void duplicate_doesNotRepublishToInboundQueue() {
        EbxmlMessageHeader h = header(true);
        when(messageRepository.existsByMessageId(MESSAGE_ID)).thenReturn(true);
        mockChannelSyncReplyMode(null);

        service.processInboundMessage(request, h, "<raw/>", FROM_OIN);

        // Inbound-queue-routing MAG NIET aangeroepen zijn bij een duplicaat.
        // (Alleen audit-events via AUDIT-routing zijn toegestaan.)
        verify(rabbitTemplate, never()).convertAndSend(
            eq(nl.logius.ebms.orchestrator.config.RabbitMqConfig.EXCHANGE_EBMS),
            eq(nl.logius.ebms.orchestrator.config.RabbitMqConfig.ROUTING_INBOUND),
            (Object) any());
    }

    @Test
    @DisplayName("Geldige gesigneerde ACK -> outbound SENT wordt DELIVERED")
    void signedAcknowledgment_validSignature_transitionsOutboundToDelivered() {
        EbmsMessageEntity outbound = mock(EbmsMessageEntity.class);
        PartnerCertificateDto certificate = PartnerCertificateDto.builder()
            .cpaId(CPA_ID).partyId(FROM_OIN).certificatePem("CERT-PEM").build();
        when(messageRepository.findByMessageIdAndDirectionAndStatus(
            MESSAGE_ID, MessageDirection.OUTBOUND, MessageStatus.SENT))
            .thenReturn(Optional.of(outbound));
        when(cpaValidationService.getPartnerCertificates(CPA_ID, FROM_OIN))
            .thenReturn(List.of(certificate));

        service.handleAcknowledgment(MESSAGE_ID, "<signed-ack/>", "ack-msg-1", true, CPA_ID, FROM_OIN);

        verify(cryptoServiceClient).verify("<signed-ack/>", "ack-msg-1", "CERT-PEM");
        verify(outbound).setStatus(MessageStatus.DELIVERED);
        verify(messageRepository).save(outbound);
    }

    @Test
    @DisplayName("Ongeldige gesigneerde ACK -> outbound blijft SENT")
    void signedAcknowledgment_invalidSignature_doesNotTransition() {
        EbmsMessageEntity outbound = mock(EbmsMessageEntity.class);
        PartnerCertificateDto certificate = PartnerCertificateDto.builder()
            .cpaId(CPA_ID).partyId(FROM_OIN).certificatePem("CERT-PEM").build();
        when(messageRepository.findByMessageIdAndDirectionAndStatus(
            MESSAGE_ID, MessageDirection.OUTBOUND, MessageStatus.SENT))
            .thenReturn(Optional.of(outbound));
        when(cpaValidationService.getPartnerCertificates(CPA_ID, FROM_OIN))
            .thenReturn(List.of(certificate));
        doThrow(new XmlSecurityException("ACK-handtekening ongeldig"))
            .when(cryptoServiceClient).verify("<invalid-ack/>", "ack-msg-2", "CERT-PEM");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.handleAcknowledgment(MESSAGE_ID, "<invalid-ack/>", "ack-msg-2", true, CPA_ID, FROM_OIN))
            .isInstanceOf(XmlSecurityException.class);

        verify(outbound, never()).setStatus(MessageStatus.DELIVERED);
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Onbekende RefToMessageId -> geen exception en geen statusmutatie")
    void acknowledgment_unknownReference_isIgnoredCleanly() {
        when(messageRepository.findByMessageIdAndDirectionAndStatus(
            "unknown-ref", MessageDirection.OUTBOUND, MessageStatus.SENT))
            .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() ->
            service.handleAcknowledgment("unknown-ref", "<ack/>", "ack-msg-3", true))
            .doesNotThrowAnyException();

        verifyNoInteractions(cryptoServiceClient);
        verify(messageRepository, never()).save(any());
    }
}
