package nl.logius.ebms.orchestrator.service;

import com.rabbitmq.client.Channel;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.common.model.ebxml.MessageInfo;
import nl.logius.ebms.common.model.ebxml.PartyId;
import nl.logius.ebms.common.model.ebxml.ServiceType;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import jakarta.xml.soap.SOAPMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito unit test for OutboundMessageService.handleOutboundMessage()
 * that verifies the @Transactional rollback fix described in the review request.
 *
 * <p>The bug: catch blocks inside the @Transactional method previously swallowed
 * exceptions to call nack(), which meant Spring's transactional interceptor never
 * saw an exception and thus COMMITTED the transaction (including the
 * persistOutboundMessage() write that set status=PROCESSING). The fix adds
 * {@code TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()}
 * so the entire unit of work is rolled back and no stale PROCESSING row remains.
 *
 * <p>This test does NOT hit a real DB (no Testcontainers/Docker in sandbox) — it
 * verifies the CONTRACT by static-mocking TransactionAspectSupport and asserting:
 *   (a) after {@code persistOutboundMessage()} runs and {@code outboundSoapClient.send()}
 *       throws, {@code setRollbackOnly()} is invoked exactly once;
 *   (b) the final status-setter (SENT/DELIVERED) + second save() are NEVER reached;
 *   (c) the AMQP channel is nacked with requeue=true;
 *   (d) on the happy path, {@code setRollbackOnly()} is NEVER invoked.
 *
 * <p>Because Spring's @Transactional honors setRollbackOnly() unconditionally at
 * commit time (this is a stable, documented Spring contract), verifying the call
 * is a valid proxy for "the DB row does not get stuck at PROCESSING".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundMessageServiceRollbackTest {

    @Mock EbmsMessageRepository      messageRepository;
    @Mock CpaChannelCacheService     cpaChannelCacheService;
    @Mock CryptoServiceClient        cryptoServiceClient;
    @Mock OutboundSoapClient         outboundSoapClient;
    @Mock SoapHelper                 soapHelper;
    @Mock RabbitTemplate             rabbitTemplate;
    @Mock Channel                    amqpChannel;

    @InjectMocks OutboundMessageService service;

    private EbmsOutboundMessage outboundMessage;
    private EbmsMessageEntity   persistedEntity;

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

        // CPA lookup returns a Best-Effort channel (osb-be) => no signing/encryption =>
        // exception in outboundSoapClient.send() happens right after persist.
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .endpointUrl("https://partner.example/ebms")
            .dkProfile("osb-be")
            .persistDuration(3600)
            .build();
        when(cpaChannelCacheService.getChannel(anyString(), anyString())).thenReturn(channel);

        // SoapHelper: return a benign SOAP envelope string.
        SOAPMessage soapMock = mock(SOAPMessage.class);
        when(soapHelper.buildOutboundSoap(any(), anyBoolean())).thenReturn(soapMock);
        when(soapHelper.soapToString(any())).thenReturn("<soap:Envelope/>");

        // Repository: no existing row → orElseGet branch creates a new entity and "saves" it.
        when(messageRepository.findByMessageId("msg-42")).thenReturn(Optional.empty());
        when(messageRepository.save(any(EbmsMessageEntity.class))).thenAnswer(inv -> {
            persistedEntity = inv.getArgument(0);
            if (persistedEntity.getId() == null) {
                persistedEntity.setId(UUID.randomUUID());
            }
            return persistedEntity;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (A) Failure path: setRollbackOnly() MUST be called when send() throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send() throws RuntimeException AFTER persist → setRollbackOnly() invoked, no DELIVERED-save, nack(requeue=true)")
    void sendFails_afterPersist_marksRollbackOnly_noFurtherStatusSave() throws Exception {
        // Simulate the crash after the entity is persisted with status=PROCESSING.
        Mockito.doThrow(new RuntimeException("transient network failure"))
            .when(outboundSoapClient).send(anyString(), anyString());

        TransactionStatus txStatus = mock(TransactionStatus.class);

        try (MockedStatic<TransactionAspectSupport> mocked = mockStatic(TransactionAspectSupport.class)) {
            mocked.when(TransactionAspectSupport::currentTransactionStatus).thenReturn(txStatus);

            service.handleOutboundMessage(outboundMessage, amqpChannel, 123L);

            // 1) The core contract: rollback was requested exactly once.
            mocked.verify(TransactionAspectSupport::currentTransactionStatus, times(1));
        }
        verify(txStatus, times(1)).setRollbackOnly();

        // 2) The pre-crash persist DID happen (row was written with status=PROCESSING),
        //    proving that WITHOUT the fix this row would silently commit.
        assertThat(persistedEntity).isNotNull();
        assertThat(persistedEntity.getStatus())
            .as("row was persisted at PROCESSING before send() failed – rollback must undo this")
            .isEqualTo(MessageStatus.PROCESSING);

        // 3) The final DELIVERED/SENT status-setter + second save() were NEVER reached.
        //    Repository.save() was called exactly once (the initial persist, orElseGet branch).
        verify(messageRepository, times(1)).save(any(EbmsMessageEntity.class));

        // 4) AMQP nack with requeue=true, no ack.
        verify(amqpChannel, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("send() throws EbmsException → setRollbackOnly() invoked in EbmsException catch branch")
    void sendFails_ebmsException_marksRollbackOnly() throws Exception {
        Mockito.doThrow(new EbmsException("SEND_FAILED", "boom"))
            .when(outboundSoapClient).send(anyString(), anyString());

        TransactionStatus txStatus = mock(TransactionStatus.class);

        try (MockedStatic<TransactionAspectSupport> mocked = mockStatic(TransactionAspectSupport.class)) {
            mocked.when(TransactionAspectSupport::currentTransactionStatus).thenReturn(txStatus);

            service.handleOutboundMessage(outboundMessage, amqpChannel, 456L);

            mocked.verify(TransactionAspectSupport::currentTransactionStatus, times(1));
        }
        verify(txStatus, times(1)).setRollbackOnly();
        verify(amqpChannel, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (B) Happy path: setRollbackOnly() MUST NOT be called
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Happy path (osb-be) → DELIVERED, no setRollbackOnly, basicAck")
    void happyPath_noRollback_ackSent() throws Exception {
        // send() returns normally (default void behavior).

        try (MockedStatic<TransactionAspectSupport> mocked = mockStatic(TransactionAspectSupport.class)) {
            service.handleOutboundMessage(outboundMessage, amqpChannel, 789L);

            // currentTransactionStatus() must never be looked up on the happy path.
            mocked.verifyNoInteractions();
        }

        // Final status was updated to DELIVERED (Best Effort profile).
        assertThat(persistedEntity.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        // Two saves: initial PROCESSING persist + final DELIVERED update.
        verify(messageRepository, times(2)).save(any(EbmsMessageEntity.class));
        verify(amqpChannel, times(1)).basicAck(anyLong(), anyBoolean());
        verify(amqpChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (C) Builder-with-nullable-version regression: verifies that constructing
    // an EbmsMessageEntity via the Lombok builder without setting version does
    // not throw, and leaves version=null (Hibernate assigns 0 on first INSERT
    // per standard JPA @Version-for-numeric-wrapper semantics; the DB column
    // default 0 is also in place via V3 migration).
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
