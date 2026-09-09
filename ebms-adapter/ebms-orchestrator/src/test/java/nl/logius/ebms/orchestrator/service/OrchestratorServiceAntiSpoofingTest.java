package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito tests for OrchestratorService.processInboundMessage() step-0
 * anti-spoofing OIN validation.
 *
 * <p>Vervangt de oude versie die direct op {@code messageRepository.save(...)} verifieerde:
 * persistentie van afgewezen berichten verloopt nu via {@link InboundMessageTrackingService}
 * (eigen {@code REQUIRES_NEW}-transactie, zie die klasse voor de root-cause). Deze test
 * verifieert daarom het contract met die tracking-service.
 *
 * Contract:
 *  (a) clientOin == eb:From/PartyId   -> processing continues normally
 *  (b) mismatch                        -> trackingService.persistFailed() + SecurityFailure, no downstream calls
 *  (c) blank clientOin + enforce=true  -> reject as SecurityFailure
 *  (d) blank clientOin + enforce=false -> continue, no rejection
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorServiceAntiSpoofingTest {

    @Mock EbmsMessageRepository messageRepository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock SoapHelper soapHelper;
    @Mock CpaValidationService cpaValidationService;
    @Mock CryptoServiceClient cryptoServiceClient;
    @Mock nl.logius.ebms.orchestrator.config.RetryProperties retryProperties;
    @Mock InboundMessageTrackingService trackingService;

    @InjectMocks OrchestratorService service;

    private SOAPMessage soapMessage;
    private EbxmlMessageHeader header;
    private static final String MESSAGE_ID = "msg-42";
    private static final String CPA_ID = "cpa-1";
    private static final String FROM_OIN = "00000000000000000001";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enforceInboundOinValidation", true);
        ReflectionTestUtils.setField(service, "decryptionKeyAlias", "encryption-key");

        soapMessage = org.mockito.Mockito.mock(SOAPMessage.class);
        header = EbxmlMessageHeader.builder()
            .cpaId(CPA_ID)
            .conversationId("conv-1")
            .from(List.of(PartyId.builder().value(FROM_OIN).type("URN:OIN").build()))
            .to(List.of(PartyId.builder().value("00000000000000000002").type("URN:OIN").build()))
            .fromRole("Sender").toRole("Receiver")
            .service(ServiceType.builder().value("urn:test:svc").type("urn:test").build())
            .action("send")
            .messageInfo(MessageInfo.builder().messageId(MESSAGE_ID).timestamp(Instant.now()).build())
            .build();

        // Happy-path defaults so case (a) can proceed all the way through.
        when(cpaValidationService.validateCpaAndOin(anyString(), nullable(String.class)))
            .thenReturn(CpaValidationResult.success(null));
        when(soapHelper.hasEncryptedBody(any())).thenReturn(false);
        when(soapHelper.hasSignature(any())).thenReturn(false);
        when(messageRepository.existsByMessageId(anyString())).thenReturn(false);
        when(soapHelper.createEmptyResponse()).thenReturn(soapMessage);
    }

    @Test
    @DisplayName("(a) clientOin == eb:From/PartyId -> processing continues, persistReceived aangeroepen, AMQP published")
    void oinMatches_processingContinues() {
        SOAPMessage response = service.processInboundMessage(soapMessage, header, "<raw/>", FROM_OIN);

        assertThat(response).isNotNull();
        verify(cpaValidationService).validateCpaAndOin(CPA_ID, FROM_OIN);
        verify(trackingService).persistReceived(eq(header), anyString(), eq(FROM_OIN));
        verify(trackingService).markDelivered(MESSAGE_ID);
        verify(trackingService, never()).persistFailed(any(), any(), any(), any());
        verify(rabbitTemplate, org.mockito.Mockito.atLeastOnce())
            .convertAndSend(anyString(), anyString(), (Object) any());
    }

    @Test
    @DisplayName("(b) clientOin != eb:From/PartyId -> trackingService.persistFailed() + SecurityFailure, no CPA/crypto/AMQP inbound")
    void oinMismatch_persistsFailedAndThrows() {
        String spoofedOin = "99999999999999999999";

        assertThatThrownBy(() ->
            service.processInboundMessage(soapMessage, header, "<raw/>", spoofedOin)
        ).isInstanceOfSatisfying(EbmsException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo("SecurityFailure");
            assertThat(ex.getMessage()).contains("identiteitspoofing");
        });

        // FAILED-persistentie met foutdetail, via de tracking-service.
        ArgumentCaptor<String> errorCap = ArgumentCaptor.forClass(String.class);
        verify(trackingService).persistFailed(eq(header), eq("<raw/>"), eq(spoofedOin), errorCap.capture());
        assertThat(errorCap.getValue()).contains(spoofedOin).contains("identiteitspoofing");

        // Downstream MUST NOT run.
        verify(cpaValidationService, never()).validateCpaAndOin(anyString(), anyString());
        verify(cryptoServiceClient, never()).decrypt(anyString(), anyString(), anyString());
        verify(cryptoServiceClient, never()).verify(anyString(), anyString());
        verify(trackingService, never()).persistReceived(any(), any(), any());
    }

    @Test
    @DisplayName("(c) blank clientOin + enforce=true -> SecurityFailure, no CPA validation")
    void blankOin_enforced_rejects() {
        assertThatThrownBy(() ->
            service.processInboundMessage(soapMessage, header, "<raw/>", null)
        ).isInstanceOfSatisfying(EbmsException.class, ex ->
            assertThat(ex.getErrorCode()).isEqualTo("SecurityFailure")
        );

        verify(cpaValidationService, never()).validateCpaAndOin(anyString(), anyString());
        // FAILED-persistentie met foutdetail dat naar de ontbrekende header verwijst.
        ArgumentCaptor<String> errorCap = ArgumentCaptor.forClass(String.class);
        verify(trackingService).persistFailed(eq(header), eq("<raw/>"), isNull(), errorCap.capture());
        assertThat(errorCap.getValue()).contains("X-Forwarded-Client-OIN");
    }

    @Test
    @DisplayName("(d) blank clientOin + enforce=false -> continues processing (warn only)")
    void blankOin_notEnforced_continues() {
        ReflectionTestUtils.setField(service, "enforceInboundOinValidation", false);

        SOAPMessage response = service.processInboundMessage(soapMessage, header, "<raw/>", null);

        assertThat(response).isNotNull();
        // Continues into CPA validation (proves no step-0 rejection).
        verify(cpaValidationService).validateCpaAndOin(eq(CPA_ID), isNull());
        verify(trackingService, never()).persistFailed(any(), any(), any(), any());
    }
}
