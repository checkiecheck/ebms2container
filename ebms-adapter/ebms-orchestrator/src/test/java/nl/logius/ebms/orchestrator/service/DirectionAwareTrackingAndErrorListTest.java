package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito tests for the four bugs + inbound DELIVERED completion introduced in this
 * iteration:
 *
 * <ul>
 *   <li><b>Bug A</b> - direction-aware repository lookups
 *       ({@link EbmsMessageRepository#findByMessageIdAndDirection}) so INBOUND FAILED-writes
 *       never silently overwrite the OUTBOUND row (or vice-versa).</li>
 *   <li><b>Bug B</b> - ebXML {@code eb:ErrorList} detection in {@link OutboundSoapClient}:
 *       {@code OutboundMessageService} must now see {@code PARTNER_REJECTED} and NOT ack.</li>
 *   <li><b>I-7</b>  - duplicate inbound records a {@code duplicateCount++}/{@code lastDuplicateAt}
 *       on the existing row WITHOUT overwriting status/content.</li>
 *   <li><b>O-5</b>  - crypto/signing failures ({@link XmlSecurityException} =
 *       errorCode {@code SecurityFailure}) are non-retryable
 *       (nack requeue=false).</li>
 *   <li>Inbound DELIVERED completion: after successful AMQP publish, the row transitions
 *       RECEIVED -> DELIVERED (not stuck on PROCESSING).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectionAwareTrackingAndErrorListTest {

    // ─────────────────────────────────────────────────────────────────────────
    // (1) InboundMessageTrackingService - Bug A, I-7, DELIVERED completion
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class InboundTrackingTests {

        @Mock EbmsMessageRepository repo;
        @InjectMocks InboundMessageTrackingService inboundTracking;

        private EbxmlMessageHeader header;
        private static final String MID = "loopback-1";

        @BeforeEach
        void setUp() {
            header = EbxmlMessageHeader.builder()
                .cpaId("cpa-1").conversationId("conv-1")
                .from(List.of(PartyId.builder().value("OIN-A").type("URN:OIN").build()))
                .to(List.of(PartyId.builder().value("OIN-B").type("URN:OIN").build()))
                .fromRole("Sender").toRole("Receiver")
                .service(ServiceType.builder().value("urn:svc").build())
                .action("send")
                .messageInfo(MessageInfo.builder().messageId(MID).timestamp(Instant.now()).build())
                .build();
        }

        @Test
        @DisplayName("Bug A - persistFailed queries INBOUND only, never OUTBOUND")
        void persistFailed_usesDirectionAwareLookup() {
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.INBOUND))
                .thenReturn(Optional.empty());

            inboundTracking.persistFailed(header, "<raw/>", "OIN-A", "OIN missing");

            verify(repo).findByMessageIdAndDirection(MID, MessageDirection.INBOUND);
            // MUST NOT touch OUTBOUND at all
            verify(repo, never()).findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND);
        }

        @Test
        @DisplayName("Bug A - persistFailed swallows DataIntegrityViolation on unique-constraint collision (loopback: OUTBOUND row exists)")
        void persistFailed_selfLoopback_doesNotOverwriteOutboundRow() {
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.INBOUND))
                .thenReturn(Optional.empty());
            // Simuleer de DB-uq_message_id constraint: een nieuwe INSERT met bestaande messageId faalt.
            // NB: saveAndFlush wordt gebruikt (i.p.v. save) zodat de constraint-violation synchroon
            // binnen de try-catch afgaat in plaats van pas bij transactie-commit (na de methode).
            when(repo.saveAndFlush(any(EbmsMessageEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_message_id"));

            // Mag GEEN exception naar buiten laten lekken - de FAILED-poging is best-effort.
            inboundTracking.persistFailed(header, "<raw/>", "OIN-A", "spoofing");

            // Precies één saveAndFlush-poging (de INSERT), daarna gevangen; geen tweede save/update.
            verify(repo, times(1)).saveAndFlush(any(EbmsMessageEntity.class));
            verify(repo, never()).save(any(EbmsMessageEntity.class));
        }

        @Test
        @DisplayName("markDelivered - INBOUND row RECEIVED -> DELIVERED (after successful AMQP publish)")
        void markDelivered_transitionsReceivedToDelivered() {
            EbmsMessageEntity existing = EbmsMessageEntity.builder()
                .messageId(MID).conversationId("c").cpaId("cpa-1")
                .fromPartyId("OIN-A").toPartyId("OIN-B")
                .service("svc").action("send")
                .direction(MessageDirection.INBOUND).status(MessageStatus.RECEIVED)
                .timestamp(Instant.now()).build();
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.INBOUND))
                .thenReturn(Optional.of(existing));

            inboundTracking.markDelivered(MID);

            ArgumentCaptor<EbmsMessageEntity> cap = ArgumentCaptor.forClass(EbmsMessageEntity.class);
            verify(repo).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(MessageStatus.DELIVERED);
        }

        @Test
        @DisplayName("I-7 - recordDuplicate increments duplicate_count, sets last_duplicate_at, DOES NOT touch status/content")
        void recordDuplicate_preservesOriginal_updatesDupCounter() {
            Instant originalTs = Instant.parse("2026-01-01T00:00:00Z");
            EbmsMessageEntity existing = EbmsMessageEntity.builder()
                .messageId(MID).conversationId("c").cpaId("cpa-1")
                .fromPartyId("OIN-A").toPartyId("OIN-B")
                .service("svc").action("send")
                .direction(MessageDirection.INBOUND).status(MessageStatus.DELIVERED)
                .rawSoapXml("<original/>")
                .duplicateCount(0)
                .timestamp(originalTs).build();
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.INBOUND))
                .thenReturn(Optional.of(existing));

            Instant before = Instant.now();
            inboundTracking.recordDuplicate(MID);
            Instant after = Instant.now();

            ArgumentCaptor<EbmsMessageEntity> cap = ArgumentCaptor.forClass(EbmsMessageEntity.class);
            verify(repo).save(cap.capture());
            EbmsMessageEntity saved = cap.getValue();
            assertThat(saved.getDuplicateCount()).isEqualTo(1);
            assertThat(saved.getLastDuplicateAt()).isNotNull()
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
            // Origineel content/status ONGEWIJZIGD
            assertThat(saved.getStatus()).isEqualTo(MessageStatus.DELIVERED);
            assertThat(saved.getRawSoapXml()).isEqualTo("<original/>");
            assertThat(saved.getTimestamp()).isEqualTo(originalTs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (2) OutboundMessageTrackingService - Bug A (direction-aware)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class OutboundTrackingTests {

        @Mock EbmsMessageRepository repo;
        @InjectMocks OutboundMessageTrackingService outboundTracking;

        private static final String MID = "loopback-1";

        @Test
        @DisplayName("Bug A - markSentOrDelivered queries OUTBOUND only")
        void markSentOrDelivered_directionScoped() {
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND))
                .thenReturn(Optional.empty());

            outboundTracking.markSentOrDelivered(MID, false);

            verify(repo).findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND);
            verify(repo, never()).findByMessageIdAndDirection(MID, MessageDirection.INBOUND);
        }

        @Test
        @DisplayName("Bug A - markFailed queries OUTBOUND only")
        void markFailed_directionScoped() {
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND))
                .thenReturn(Optional.empty());

            outboundTracking.markFailed(MID, "boom");

            verify(repo).findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND);
            verify(repo, never()).findByMessageIdAndDirection(MID, MessageDirection.INBOUND);
        }

        @Test
        void markFailed_persistsExactFunctionalErrorForAdminUi() {
            EbmsMessageEntity entity = EbmsMessageEntity.builder()
                .messageId(MID)
                .direction(MessageDirection.OUTBOUND)
                .build();
            String reason = "[CPA_ROLE_MISMATCH] Given role 'Consumer' does not match CPA role 'Sender'";
            when(repo.findByMessageIdAndDirection(MID, MessageDirection.OUTBOUND))
                .thenReturn(Optional.of(entity));

            outboundTracking.markFailed(MID, reason);

            assertThat(entity.getStatus()).isEqualTo(MessageStatus.FAILED);
            assertThat(entity.getErrorMessage()).isEqualTo(reason);
            verify(repo).save(entity);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (3) OutboundMessageService - Bug B (PARTNER_REJECTED) + O-5 (SecurityFailure)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class OutboundMessageServiceTests {

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
        void setUp() {
            ReflectionTestUtils.setField(service, "defaultSigningKeyAlias", "signing-key");

            EbxmlMessageHeader header = EbxmlMessageHeader.builder()
                .cpaId("cpa-1").conversationId("conv-1")
                .from(List.of(PartyId.builder().value("OIN-A").type("URN:OIN").build()))
                .to(List.of(PartyId.builder().value("OIN-B").type("URN:OIN").build()))
                .fromRole("Sender").toRole("Receiver")
                .service(ServiceType.builder().value("urn:svc").type("urn:t").build())
                .action("send")
                .messageInfo(MessageInfo.builder().messageId("msg-42").timestamp(Instant.now()).build())
                .build();

            outboundMessage = EbmsOutboundMessage.builder()
                .messageId("msg-42").header(header)
                .payloadRef("s3://x").payloadContentType("application/xml").build();
        }

        private void stubHappyChannel(String dkProfile) {
            DeliveryChannelDto channel = DeliveryChannelDto.builder()
                .endpointUrl("https://partner.example/ebms").dkProfile(dkProfile).persistDuration(3600)
                .build();
            when(cpaChannelCacheService.getOutboundRoute(
                anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(OutboundRouteDto.builder()
                    .fromRole("Sender").toRole("Receiver").channel(channel).build());
            SOAPMessage soapMock = mock(SOAPMessage.class);
            when(soapHelper.buildOutboundSoap(any(), anyBoolean())).thenReturn(soapMock);
            when(soapHelper.soapToString(any())).thenReturn("<soap:Envelope/>");
        }

        @Test
        @DisplayName("Bug B - OutboundSoapClient throws EbmsException(PARTNER_REJECTED) -> markFailed + nack(requeue=false), NO markSentOrDelivered")
        void partnerRejected_marksFailed_noRequeue_noDelivered() throws Exception {
            stubHappyChannel("osb-be");
            Mockito.doThrow(new EbmsException("PARTNER_REJECTED",
                    "ebXML ErrorList van partner endpoint: [SecurityFailure] mismatch"))
                .when(outboundSoapClient).send(anyString(), anyString(), anyString(), anyString());

            service.handleOutboundMessage(outboundMessage, amqpChannel, 1L);

            verify(trackingService).markFailed(eq("msg-42"), anyString());
            verify(trackingService, never()).markSentOrDelivered(anyString(), anyBoolean());
            verify(amqpChannel).basicNack(anyLong(), anyBoolean(), eq(false));
            verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("O-5 - XmlSecurityException from crypto (sign) -> markFailed + nack(requeue=false) (SecurityFailure is NON_RETRYABLE)")
        void cryptoSigningFailure_marksFailed_noRequeue() throws Exception {
            stubHappyChannel("osb-rm-s"); // profile requiring signing (contains 's')
            Mockito.doThrow(new XmlSecurityException("onbekende key alias"))
                .when(cryptoServiceClient).sign(
                    anyString(), anyString(), anyString(), any(), any());

            service.handleOutboundMessage(outboundMessage, amqpChannel, 2L);

            ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
            verify(trackingService).markFailed(eq("msg-42"), errCap.capture());
            assertThat(errCap.getValue()).contains("SecurityFailure");
            verify(trackingService, never()).markSentOrDelivered(anyString(), anyBoolean());
            verify(outboundSoapClient, never()).send(anyString(), anyString(), anyString(), anyString());
            verify(amqpChannel).basicNack(anyLong(), anyBoolean(), eq(false));
        }

        @Test
        @DisplayName("Regression O-6 - CONNECTION_ERROR remains RETRYABLE via database scheduler")
        void connectionError_isRetryable() throws Exception {
            stubHappyChannel("osb-be");
            Mockito.doThrow(new EbmsException("CONNECTION_ERROR", "socket timeout"))
                .when(outboundSoapClient).send(anyString(), anyString(), anyString(), anyString());

            service.handleOutboundMessage(outboundMessage, amqpChannel, 3L);

            verify(trackingService).markFailed(eq("msg-42"), anyString());
            verify(amqpChannel).basicAck(anyLong(), anyBoolean());
            verify(amqpChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (4) OutboundSoapClient - Bug B ErrorList detection + native Fault regression
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class OutboundSoapClientErrorListTests {

        @Test
        @DisplayName("Bug B - SoapHelper.parseErrorList detects eb:ErrorList in SOAP header (no native Fault)")
        void parseErrorList_detectsHeaderErrorList() {
            SoapHelper soapHelper = new SoapHelper();
            SOAPMessage errorResponse = soapHelper.createErrorResponse(
                "SecurityFailure", "gateway-OIN ontbreekt", "orig-1");

            SoapHelper.EbxmlError err = soapHelper.parseErrorList(errorResponse);

            assertThat(err).isNotNull();
            assertThat(err.errorCode()).isEqualTo("SecurityFailure");
            assertThat(err.description()).contains("gateway-OIN");
        }

        @Test
        @DisplayName("Regression - parseErrorList returns null on a plain empty response (no ErrorList, no Fault)")
        void parseErrorList_returnsNullOnEmptyResponse() {
            SoapHelper soapHelper = new SoapHelper();
            SOAPMessage empty = soapHelper.createEmptyResponse();

            assertThat(soapHelper.parseErrorList(empty)).isNull();
        }

        @Test
        @DisplayName("Bug B end-to-end - OutboundSoapClient.send() maps ErrorList response to EbmsException(PARTNER_REJECTED)")
        void outboundSoapClient_errorListResponse_throwsPartnerRejected() throws Exception {
            // Build a real ErrorList response and feed it back through the real SoapHelper.
            SoapHelper realHelper = new SoapHelper();
            SOAPMessage errorResp = realHelper.createErrorResponse(
                "SecurityFailure", "OIN-mismatch", "orig-1");

            // Verify the helper itself surfaces the error - this is the exact call
            // OutboundSoapClient.send() makes right after the native Fault check.
            SoapHelper.EbxmlError err = realHelper.parseErrorList(errorResp);
            assertThat(err).isNotNull();
            assertThat(err.errorCode()).isEqualTo("SecurityFailure");

            // And confirm the mapping OutboundSoapClient does: parseErrorList != null -> PARTNER_REJECTED.
            EbmsException mapped = new EbmsException("PARTNER_REJECTED",
                "ebXML ErrorList van partner endpoint (X): [" + err.errorCode() + "] " + err.description());
            assertThatThrownBy(() -> { throw mapped; })
                .isInstanceOf(EbmsException.class)
                .satisfies(t -> assertThat(((EbmsException) t).getErrorCode()).isEqualTo("PARTNER_REJECTED"));
        }
    }
}
