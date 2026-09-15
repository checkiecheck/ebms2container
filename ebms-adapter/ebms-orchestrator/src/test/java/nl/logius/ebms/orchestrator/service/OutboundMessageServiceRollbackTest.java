package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.xml.soap.SOAPMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito unit test for {@code OutboundMessageService.handleOutboundMessage()}.
 *
 * <p>Vervangt de oude {@code setRollbackOnly()}-gebaseerde test: die aanpak liet een falend
 * bericht (bv. signing/verzenden mislukt) volledig spoorloos verdwijnen uit {@code ebms_message},
 * omdat de hele methode onder één {@code @Transactional} stond en elke exception alles terugrolde
 * — óók de net geschreven PROCESSING-rij. De fix: {@code OutboundMessageService} heeft geen
 * {@code @Transactional} meer en delegeert alle DB-boekhouding aan
 * {@link OutboundMessageTrackingService}, die in eigen ({@code REQUIRES_NEW}) transacties werkt.
 * Deze test verifieert daarom het CONTRACT met die tracking-service in plaats van transactie-
 * interne Spring-mechanismen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundMessageServiceRollbackTest {

    @Mock CpaChannelCacheService        cpaChannelCacheService;
    @Mock CryptoServiceClient           cryptoServiceClient;
    @Mock OutboundSoapClient            outboundSoapClient;
    @Mock SoapHelper                    soapHelper;
    @Mock RabbitTemplate                rabbitTemplate;
    @Mock OutboundMessageTrackingService trackingService;
    @Mock Channel                       amqpChannel;

    @InjectMocks OutboundMessageService service;

    private EbmsOutboundMessage outboundMessage;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(service, "defaultSigningKeyAlias", "signing-key");

        EbxmlMessageHeader header = EbxmlMessageHeader.builder()
            .cpaId("cpa-1")
            .conversationId("conv-1")
            .from(List.of(PartyId.builder().value("00000000000000000001").type("URN:OIN").build()))
            .to(List.of(PartyId.builder().value("00000000000000000002").type("URN:OIN").build()))
            .fromRole("Sender")
            .toRole("Receiver")
            .service(ServiceType.builder().value("urn:test:service").type("urn:test").build())
            .action("send")
            .messageInfo(MessageInfo.builder().messageId("msg-42").timestamp(Instant.now()).build())
            .build();

        outboundMessage = EbmsOutboundMessage.builder()
            .messageId("msg-42")
            .header(header)
            .payloadRef("s3://bucket/payload")
            .payloadContentType("application/xml")
            .build();

        // CPA lookup returns a Best-Effort channel (osb-be) => no signing/encryption.
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .endpointUrl("https://partner.example/ebms")
            .dkProfile("osb-be")
            .persistDuration(3600)
            .build();
        when(cpaChannelCacheService.getOutboundRoute(
            anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(OutboundRouteDto.builder()
                .fromRole("Sender")
                .toRole("Receiver")
                .channel(channel)
                .build());

        SOAPMessage soapMock = mock(SOAPMessage.class);
        when(soapHelper.buildOutboundSoap(any(), anyBoolean())).thenReturn(soapMock);
        when(soapHelper.soapToString(any())).thenReturn("<soap:Envelope/>");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (A) Failure path: markFailed() MUST be invoked, markSentOrDelivered() NEVER
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send() throws RuntimeException → trackingService.markFailed() invoked, geen markSentOrDelivered, basicAck")
    void sendFails_marksFailed_noSentOrDelivered() throws Exception {
        Mockito.doThrow(new RuntimeException("transient network failure"))
            .when(outboundSoapClient).send(anyString(), anyString(), anyString(), anyString());

        service.handleOutboundMessage(outboundMessage, amqpChannel, 123L);

        // Vroege persist (PROCESSING) + enrich-persist (na SOAP-opbouw) zijn allebei geprobeerd.
        verify(trackingService, times(2)).createOrUpdateProcessing(
            eq("msg-42"), eq(outboundMessage), any(), any(), any());

        // De fout is vastgelegd als FAILED, ONAFHANKELIJK van de rest van de flow.
        verify(trackingService, times(1)).markFailed(eq("msg-42"), anyString());
        verify(trackingService, never()).markSentOrDelivered(anyString(), anyBoolean());

        verify(amqpChannel, times(1)).basicAck(anyLong(), anyBoolean());
        verify(amqpChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("send() throws EbmsException (herstelbaar) → markFailed() + basicAck zonder requeue")
    void sendFails_ebmsException_retryable_isAckedForDatabaseRetry() throws Exception {
        Mockito.doThrow(new EbmsException("NETWORK_FAILURE", "temporary outage"))
            .when(outboundSoapClient).send(anyString(), anyString(), anyString(), anyString());

        service.handleOutboundMessage(outboundMessage, amqpChannel, 234L);

        verify(trackingService, times(1)).markFailed(eq("msg-42"), anyString());
        verify(amqpChannel, times(1)).basicAck(anyLong(), anyBoolean());
        verify(amqpChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("send() throws EbmsException (niet-herstelbare code) → markFailed() + nack(requeue=false)")
    void sendFails_ebmsException_nonRetryable_noRequeue() throws Exception {
        Mockito.doThrow(new EbmsException("CHANNEL_NOT_FOUND", "boom"))
            .when(outboundSoapClient).send(anyString(), anyString(), anyString(), anyString());

        service.handleOutboundMessage(outboundMessage, amqpChannel, 456L);

        verify(trackingService, times(1)).markFailed(eq("msg-42"), anyString());
        verify(amqpChannel, times(1)).basicNack(anyLong(), anyBoolean(), eq(false));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (B) Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Happy path (osb-be) → markSentOrDelivered(requireAck=false), geen markFailed, basicAck")
    void happyPath_marksDelivered_ackSent() throws Exception {
        service.handleOutboundMessage(outboundMessage, amqpChannel, 789L);

        verify(trackingService, times(2)).createOrUpdateProcessing(
            eq("msg-42"), eq(outboundMessage), any(), any(), any());
        verify(trackingService, times(1)).markSentOrDelivered("msg-42", false);
        verify(trackingService, never()).markFailed(anyString(), anyString());

        verify(amqpChannel, times(1)).basicAck(anyLong(), anyBoolean());
        verify(amqpChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void missingRoles_areFilledFromExactCpaRouteBeforeSoapCreation() throws Exception {
        outboundMessage.getHeader().setFromRole(null);
        outboundMessage.getHeader().setToRole(null);

        service.handleOutboundMessage(outboundMessage, amqpChannel, 790L);

        assertThat(outboundMessage.getHeader().getFromRole()).isEqualTo("Sender");
        assertThat(outboundMessage.getHeader().getToRole()).isEqualTo("Receiver");
        verify(soapHelper).buildOutboundSoap(outboundMessage.getHeader(), false);
    }

    @Test
    void roleWithDifferentCase_isRejectedBeforeSoapCreation() throws Exception {
        outboundMessage.getHeader().setFromRole("sender");

        service.handleOutboundMessage(outboundMessage, amqpChannel, 791L);

        verify(trackingService).markFailed(eq("msg-42"),
            org.mockito.ArgumentMatchers.startsWith("[CPA_ROLE_MISMATCH] "));
        verify(soapHelper, never()).buildOutboundSoap(any(), anyBoolean());
        verify(amqpChannel).basicNack(791L, false, false);
    }

    @Test
    void routeNotFound_persistsExactReasonBeforeRejectingMessage() throws Exception {
        when(cpaChannelCacheService.getOutboundRoute(
            anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenThrow(new EbmsException("ROUTE_NOT_FOUND",
                "Geen outbound CPA-route voor service=urn:test:service action=send"));

        service.handleOutboundMessage(outboundMessage, amqpChannel, 792L);

        verify(trackingService).markFailed("msg-42",
            "[ROUTE_NOT_FOUND] Geen outbound CPA-route voor service=urn:test:service action=send");
        verify(soapHelper, never()).buildOutboundSoap(any(), anyBoolean());
        verify(amqpChannel).basicNack(792L, false, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (C) messageId-resolutie: fallback + hard-reject als beide ontbreken
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Top-level messageId ontbreekt → valt terug op header.messageInfo.messageId")
    void missingTopLevelMessageId_fallsBackToHeaderMessageInfo() throws Exception {
        EbmsOutboundMessage withoutTopLevelId = EbmsOutboundMessage.builder()
            .header(outboundMessage.getHeader()) // messageInfo.messageId = "msg-42"
            .payloadRef("s3://bucket/payload")
            .build();

        service.handleOutboundMessage(withoutTopLevelId, amqpChannel, 321L);

        verify(trackingService, times(1)).markSentOrDelivered("msg-42", false);
        verify(amqpChannel, times(1)).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("messageId ontbreekt zowel top-level als in header.messageInfo → nack(requeue=false), geen persist-poging")
    void missingMessageIdEverywhere_rejectedWithoutPersist() throws Exception {
        EbxmlMessageHeader headerWithoutMessageInfo = EbxmlMessageHeader.builder()
            .cpaId("cpa-1")
            .conversationId("conv-1")
            .from(List.of(PartyId.builder().value("1").type("URN:OIN").build()))
            .to(List.of(PartyId.builder().value("2").type("URN:OIN").build()))
            .service(ServiceType.builder().value("urn:test:service").build())
            .action("send")
            .build();
        EbmsOutboundMessage withoutAnyId = EbmsOutboundMessage.builder()
            .header(headerWithoutMessageInfo)
            .build();

        service.handleOutboundMessage(withoutAnyId, amqpChannel, 654L);

        verifyNoInteractions(trackingService);
        verify(amqpChannel, times(1)).basicNack(anyLong(), anyBoolean(), eq(false));
    }

    @Test
    @DisplayName("Null header → nack(requeue=false), geen tracking-service-aanroep")
    void nullHeader_rejectedWithoutPersist() throws Exception {
        EbmsOutboundMessage noHeader = EbmsOutboundMessage.builder().messageId("msg-99").build();

        service.handleOutboundMessage(noHeader, amqpChannel, 111L);

        verifyNoInteractions(trackingService);
        verify(amqpChannel, times(1)).basicNack(anyLong(), anyBoolean(), eq(false));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (D) Builder-with-nullable-version regression (ongewijzigd, los van deze service)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EbmsMessageEntity builder does not require version → in-memory version is null (Hibernate/DB assigns 0)")
    void entityBuilder_withoutExplicitVersion_isNullPreInsert() {
        EbmsMessageEntity e = EbmsMessageEntity.builder()
            .messageId("m1")
            .conversationId("c1")
            .cpaId("cpa-1")
            .fromPartyId("f")
            .toPartyId("t")
            .service("svc")
            .action("act")
            .timestamp(Instant.now())
            .build();
        assertThat(e.getVersion())
            .as("Long @Version field is nullable pre-persist; Hibernate sets it to 0 on first INSERT")
            .isNull();
    }
}
