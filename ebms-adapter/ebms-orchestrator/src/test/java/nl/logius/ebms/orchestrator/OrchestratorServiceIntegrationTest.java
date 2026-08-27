package nl.logius.ebms.orchestrator;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.service.CpaValidationResult;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integratietest voor {@link OrchestratorService} met echte PostgreSQL- en RabbitMQ-containers.
 *
 * <p>Getest: retry-scheduler, TTL-expiry en berichtpersistentie.
 * {@link CpaValidationService} is gemockt om HTTP-afhankelijkheid te vermijden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrchestratorServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitmq =
        new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // RabbitMQ – gebruik standaard virtual-host "/"
        registry.add("spring.rabbitmq.host",         rabbitmq::getHost);
        registry.add("spring.rabbitmq.port",         rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username",     rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password",     rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");

        // cpa-service URL hoeft niet bereikbaar te zijn (CpaValidationService is gemockt)
        registry.add("ebms.cpa-service-url", () -> "http://localhost:19999");
    }

    @MockitoBean
    CpaValidationService cpaValidationService;

    @Autowired
    EbmsMessageRepository messageRepository;

    @Autowired
    OrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        // Standaard: CPA-validatie slaagt
        CpaDto mockCpa = CpaDto.builder().cpaId("urn:test:cpa:001").status("ACTIVE").cpaXml("<cpa/>").build();
        when(cpaValidationService.validateCpaAndOin(anyString(), any()))
            .thenReturn(CpaValidationResult.success(mockCpa));
    }

    // ── Retry-scheduler ───────────────────────────────────────────────────────

    @Test
    void retryFailedMessages_failedMessageWithinRetryLimit_statusChangedToProcessing() {
        EbmsMessageEntity failedMsg = buildMessage("msg-retry-001");
        failedMsg.setDirection(MessageDirection.OUTBOUND);
        failedMsg.setStatus(MessageStatus.FAILED);
        failedMsg.setRetryCount((short) 1);
        failedMsg.setLastRetryAt(Instant.now().minusSeconds(300));
        messageRepository.save(failedMsg);

        orchestratorService.retryFailedMessages();

        EbmsMessageEntity updated = messageRepository.findByMessageId("msg-retry-001").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(updated.getRetryCount()).isEqualTo((short) 2);
    }

    @Test
    void retryFailedMessages_maxRetriesReached_isSkipped() {
        EbmsMessageEntity maxRetried = buildMessage("msg-maxretry-001");
        maxRetried.setDirection(MessageDirection.OUTBOUND);
        maxRetried.setStatus(MessageStatus.FAILED);
        maxRetried.setRetryCount((short) 3); // maxRetries=3, dus niet opnieuw
        maxRetried.setLastRetryAt(Instant.now().minusSeconds(300));
        messageRepository.save(maxRetried);

        orchestratorService.retryFailedMessages();

        EbmsMessageEntity unchanged = messageRepository.findByMessageId("msg-maxretry-001").orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(unchanged.getRetryCount()).isEqualTo((short) 3);
    }

    @Test
    void retryFailedMessages_inboundDirection_isNeverRetried() {
        // INBOUND FAILED berichten mogen NOOIT als outbound-retry-kandidaat gekozen worden
        // (regressie-test voor de bug: inbound werd voorheen ten onrechte als outbound behandeld).
        EbmsMessageEntity inboundFailed = buildMessage("msg-inbound-failed-001");
        inboundFailed.setDirection(MessageDirection.INBOUND);
        inboundFailed.setStatus(MessageStatus.FAILED);
        inboundFailed.setRetryCount((short) 0);
        messageRepository.save(inboundFailed);

        orchestratorService.retryFailedMessages();

        EbmsMessageEntity unchanged = messageRepository.findByMessageId("msg-inbound-failed-001").orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(unchanged.getRetryCount()).isEqualTo((short) 0);
    }

    // ── TTL-expiry ────────────────────────────────────────────────────────────

    @Test
    void expireMessages_expiredMessage_isMarkedAsFailed() {
        EbmsMessageEntity expired = buildMessage("msg-expired-001");
        expired.setTimeToLive(Instant.now().minusSeconds(3600)); // al verlopen
        expired.setStatus(MessageStatus.PROCESSING);
        messageRepository.save(expired);

        orchestratorService.expireMessages();

        EbmsMessageEntity updated = messageRepository.findByMessageId("msg-expired-001").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    void expireMessages_notYetExpired_remainsUnchanged() {
        EbmsMessageEntity notExpired = buildMessage("msg-notexpired-001");
        notExpired.setTimeToLive(Instant.now().plusSeconds(3600)); // nog geldig
        notExpired.setStatus(MessageStatus.PROCESSING);
        messageRepository.save(notExpired);

        orchestratorService.expireMessages();

        EbmsMessageEntity unchanged = messageRepository.findByMessageId("msg-notexpired-001").orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageStatus.PROCESSING);
    }

    // ── Repository-queries ────────────────────────────────────────────────────

    @Test
    void findMessagesForRetry_neverRetried_isCandidate() {
        EbmsMessageEntity neverRetried = buildMessage("msg-newretry-001");
        neverRetried.setDirection(MessageDirection.OUTBOUND);
        neverRetried.setStatus(MessageStatus.FAILED);
        neverRetried.setRetryCount((short) 0);
        // lastRetryAt = null → altijd kandidaat
        messageRepository.save(neverRetried);

        List<EbmsMessageEntity> candidates = messageRepository.findMessagesForRetry(
            3, Instant.now());

        assertThat(candidates)
            .extracting(EbmsMessageEntity::getMessageId)
            .contains("msg-newretry-001");
    }

    @Test
    void findMessagesForRetry_inboundDirection_isNeverCandidate() {
        EbmsMessageEntity inboundFailed = buildMessage("msg-newretry-inbound-001");
        inboundFailed.setDirection(MessageDirection.INBOUND);
        inboundFailed.setStatus(MessageStatus.FAILED);
        inboundFailed.setRetryCount((short) 0);
        messageRepository.save(inboundFailed);

        List<EbmsMessageEntity> candidates = messageRepository.findMessagesForRetry(
            3, Instant.now());

        assertThat(candidates)
            .extracting(EbmsMessageEntity::getMessageId)
            .doesNotContain("msg-newretry-inbound-001");
    }

    @Test
    void existsByMessageId_afterSave_returnsTrue() {
        EbmsMessageEntity entity = buildMessage("msg-exist-001");
        messageRepository.save(entity);

        assertThat(messageRepository.existsByMessageId("msg-exist-001")).isTrue();
        assertThat(messageRepository.existsByMessageId("urn:niet:bestaand")).isFalse();
    }

    // ── Test-hulpfuncties ─────────────────────────────────────────────────────

    private EbmsMessageEntity buildMessage(String messageId) {
        return EbmsMessageEntity.builder()
            .messageId(messageId)
            .conversationId("conv-" + messageId)
            .cpaId("urn:test:cpa:001")
            .fromPartyId("00000000000000000001")
            .toPartyId("00000000000000000002")
            .service("urn:test:service")
            .action("TestAction")
            .direction(MessageDirection.INBOUND)
            .status(MessageStatus.RECEIVED)
            .timestamp(Instant.now())
            .build();
    }
}
