package nl.logius.ebms.orchestrator.scheduler;

import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito unit test for {@link MessageStatusReconciliationScheduler}:
 *  - threshold = now - stuck-processing-timeout-minutes
 *  - stuck entities are marked FAILED with a non-null errorMessage
 *  - saveAll() called exactly once with the collected list
 *  - noop (no saveAll) when repository returns empty list
 */
@ExtendWith(MockitoExtension.class)
class MessageStatusReconciliationSchedulerTest {

    @Mock EbmsMessageRepository repo;

    @InjectMocks MessageStatusReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "stuckProcessingTimeoutMinutes", 5);
    }

    @Test
    @DisplayName("stuck entities => marked FAILED with errorMessage, saveAll called once, threshold ~ now-5min")
    void marksStuckAsFailedAndSaves() {
        EbmsMessageEntity a = mkEntity("m-A");
        EbmsMessageEntity b = mkEntity("m-B");
        ArgumentCaptor<Instant> thresholdCap = ArgumentCaptor.forClass(Instant.class);
        when(repo.findStuckProcessingMessages(thresholdCap.capture()))
            .thenReturn(List.of(a, b));

        Instant before = Instant.now();
        scheduler.reconcileStuckProcessingMessages();
        Instant after = Instant.now();

        // Threshold should be roughly now - 5 minutes.
        Instant threshold = thresholdCap.getValue();
        assertThat(threshold).isAfterOrEqualTo(before.minus(5, ChronoUnit.MINUTES).minusSeconds(2));
        assertThat(threshold).isBeforeOrEqualTo(after.minus(5, ChronoUnit.MINUTES).plusSeconds(2));

        assertThat(a.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(a.getErrorMessage()).isNotBlank().contains("Watchdog");
        assertThat(b.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(b.getErrorMessage()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EbmsMessageEntity>> listCap = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(listCap.capture());
        assertThat(listCap.getValue()).containsExactly(a, b);
    }

    @Test
    @DisplayName("empty repository result => saveAll NEVER called (noop)")
    void emptyResult_isNoop() {
        when(repo.findStuckProcessingMessages(any())).thenReturn(Collections.emptyList());

        scheduler.reconcileStuckProcessingMessages();

        verify(repo, never()).saveAll(any());
    }

    private EbmsMessageEntity mkEntity(String id) {
        return EbmsMessageEntity.builder()
            .id(UUID.randomUUID())
            .messageId(id)
            .conversationId("c")
            .cpaId("cpa")
            .fromPartyId("f").toPartyId("t")
            .service("svc").action("act")
            .status(MessageStatus.PROCESSING)
            .timestamp(Instant.now())
            .build();
    }
}
