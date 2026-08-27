package nl.logius.ebms.orchestrator.service;

import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.config.RetryProperties;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test focused on {@link OrchestratorService#retryFailedMessages()} and its
 * private {@code buildRetryHeader()} helper. Verifies that the {@link EbxmlMessageHeader}
 * previously missing on retry AMQP messages ("ontbrekende header" rejection at
 * OutboundMessageService) is now correctly reconstructed from the persisted entity.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorServiceRetryHeaderTest {

    @Mock EbmsMessageRepository messageRepository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock SoapHelper soapHelper;
    @Mock CpaValidationService cpaValidationService;
    @Mock CryptoServiceClient cryptoServiceClient;

    RetryProperties retryProperties;

    @InjectMocks OrchestratorService service;

    @BeforeEach
    void init() {
        retryProperties = new RetryProperties();
        retryProperties.setMaxRetries(5);
        retryProperties.setRetryIntervalSeconds(60);
        // inject the concrete RetryProperties (not a mock)
        org.springframework.test.util.ReflectionTestUtils.setField(service,
            "retryProperties", retryProperties);
    }

    private EbmsMessageEntity outboundCandidate() {
        return EbmsMessageEntity.builder()
            .id(UUID.randomUUID())
            .messageId("msg-001")
            .refToMessageId("ref-000")
            .conversationId("conv-XYZ")
            .cpaId("cpa-42")
            .fromPartyId("00000001001234567890")
            .fromPartyType("urn:oasis:names:tc:ebxml-cppa:partyid-type:iso6523:0106")
            .fromRole("Sender")
            .toPartyId("00000001009876543210")
            .toPartyType("urn:oasis:names:tc:ebxml-cppa:partyid-type:iso6523:0106")
            .toRole("Receiver")
            .service("urn:foo:service")
            .serviceType("bar")
            .action("submitMessage")
            .direction(MessageDirection.OUTBOUND)
            .status(MessageStatus.FAILED)
            .retryCount((short) 1)
            .ackRequested(true)
            .payloadRef("s3://bucket/payload-001.bin")
            .payloadContentType("application/octet-stream")
            .timestamp(Instant.parse("2025-01-01T00:00:00Z"))
            .build();
    }

    @Test
    @DisplayName("retryFailedMessages: retry AMQP message has a fully-reconstructed non-null header")
    void retry_populatesHeader() {
        EbmsMessageEntity candidate = outboundCandidate();
        when(messageRepository.findMessagesForRetry(anyInt(), any(Instant.class)))
            .thenReturn(List.of(candidate));

        service.retryFailedMessages();

        // Capture the message published to RabbitMQ
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
            eq(RabbitMqConfig.EXCHANGE_EBMS),
            eq(RabbitMqConfig.ROUTING_OUTBOUND),
            payloadCap.capture());

        Object sent = payloadCap.getValue();
        assertThat(sent).isInstanceOf(EbmsOutboundMessage.class);
        EbmsOutboundMessage out = (EbmsOutboundMessage) sent;

        assertThat(out.getMessageId()).isEqualTo("msg-001");
        assertThat(out.getPayloadRef()).isEqualTo("s3://bucket/payload-001.bin");
        assertThat(out.getPayloadContentType()).isEqualTo("application/octet-stream");
        assertThat(out.getScheduledAt()).isNotNull();

        // Header reconstruction is the core of this bugfix
        EbxmlMessageHeader h = out.getHeader();
        assertThat(h).as("Retry header must be reconstructed (was previously null)").isNotNull();
        assertThat(h.getCpaId()).isEqualTo("cpa-42");
        assertThat(h.getConversationId()).isEqualTo("conv-XYZ");
        assertThat(h.getAction()).isEqualTo("submitMessage");
        assertThat(h.getFromRole()).isEqualTo("Sender");
        assertThat(h.getToRole()).isEqualTo("Receiver");

        assertThat(h.getFrom()).hasSize(1);
        assertThat(h.getFrom().get(0).getValue()).isEqualTo("00000001001234567890");
        assertThat(h.getFrom().get(0).getType())
            .isEqualTo("urn:oasis:names:tc:ebxml-cppa:partyid-type:iso6523:0106");

        assertThat(h.getTo()).hasSize(1);
        assertThat(h.getTo().get(0).getValue()).isEqualTo("00000001009876543210");

        assertThat(h.getService()).isNotNull();
        assertThat(h.getService().getValue()).isEqualTo("urn:foo:service");
        assertThat(h.getService().getType()).isEqualTo("bar");

        assertThat(h.getMessageInfo()).isNotNull();
        assertThat(h.getMessageInfo().getMessageId()).isEqualTo("msg-001");
        assertThat(h.getMessageInfo().getRefToMessageId()).isEqualTo("ref-000");
        assertThat(h.getMessageInfo().getTimestamp()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));

        // ackRequested=true on entity should yield a non-null AckRequested block
        assertThat(h.getAckRequested()).as("ackRequested=true entity -> non-null AckRequested").isNotNull();
    }

    @Test
    @DisplayName("retryFailedMessages: ackRequested=false entity yields header.ackRequested=null")
    void retry_ackRequestedFalse_leavesAckNull() {
        EbmsMessageEntity candidate = outboundCandidate();
        candidate.setAckRequested(false);
        when(messageRepository.findMessagesForRetry(anyInt(), any(Instant.class)))
            .thenReturn(List.of(candidate));

        service.retryFailedMessages();

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE_EBMS),
            eq(RabbitMqConfig.ROUTING_OUTBOUND), cap.capture());
        EbmsOutboundMessage out = (EbmsOutboundMessage) cap.getValue();
        assertThat(out.getHeader()).isNotNull();
        assertThat(out.getHeader().getAckRequested()).isNull();
    }

    @Test
    @DisplayName("retryFailedMessages: entity is bumped to PROCESSING with incremented retryCount")
    void retry_updatesEntityState() {
        EbmsMessageEntity candidate = outboundCandidate();
        short originalRetryCount = candidate.getRetryCount();
        when(messageRepository.findMessagesForRetry(anyInt(), any(Instant.class)))
            .thenReturn(List.of(candidate));

        service.retryFailedMessages();

        assertThat(candidate.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(candidate.getRetryCount()).isEqualTo((short) (originalRetryCount + 1));
        assertThat(candidate.getLastRetryAt()).isNotNull();
        verify(messageRepository).save(candidate);
    }

    @Test
    @DisplayName("retryFailedMessages: empty candidate list => nothing published, nothing saved")
    void retry_noCandidates_noop() {
        when(messageRepository.findMessagesForRetry(anyInt(), any(Instant.class)))
            .thenReturn(Collections.emptyList());

        service.retryFailedMessages();

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        verify(messageRepository, never()).save(any(EbmsMessageEntity.class));
    }
}
