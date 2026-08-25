package nl.logius.ebms.orchestrator.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Watchdog-scheduler die berichten opspoort die vastzitten in de {@code PROCESSING}-status
 * (bijv. door een gecrashte verwerkingsthread, een ontbrekende AMQP-ack, of een MSH-instantie
 * die onverwacht werd afgesloten tijdens verwerking) en markeert deze als {@code FAILED}.
 *
 * <p>Zonder deze reconciliator blijven zulke berichten voor altijd "vastgeplakt" op PROCESSING:
 * ze worden nooit opgepikt door de reguliere
 * {@link nl.logius.ebms.orchestrator.service.OrchestratorService#retryFailedMessages()}
 * retry-scheduler, die uitsluitend berichten met status {@code FAILED} oppikt.
 *
 * <p>Configuratie ({@code application.yml} prefix {@code ebms.watchdog}):
 * <ul>
 *   <li>{@code stuck-processing-timeout-minutes} – threshold in minuten (standaard 5)</li>
 *   <li>{@code check-interval-ms} – scheduler-interval in ms (standaard 300000 = 5 min)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageStatusReconciliationScheduler {

    private final EbmsMessageRepository messageRepository;

    @Value("${ebms.watchdog.stuck-processing-timeout-minutes:5}")
    private int stuckProcessingTimeoutMinutes;

    /**
     * Zoekt berichten die langer dan {@code stuckProcessingTimeoutMinutes} vaststaan op
     * PROCESSING en markeert deze als FAILED met een verklarende foutmelding.
     */
    @Scheduled(fixedDelayString = "${ebms.watchdog.check-interval-ms:300000}")
    @Transactional
    public void reconcileStuckProcessingMessages() {
        Instant threshold = Instant.now().minusSeconds(stuckProcessingTimeoutMinutes * 60L);

        List<EbmsMessageEntity> stuck = messageRepository.findStuckProcessingMessages(threshold);
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("[WATCHDOG] {} bericht(en) vastgeplakt op PROCESSING (> {} min) - markeren als FAILED",
            stuck.size(), stuckProcessingTimeoutMinutes);

        for (EbmsMessageEntity msg : stuck) {
            msg.setStatus(MessageStatus.FAILED);
            msg.setErrorMessage("Watchdog: bericht bleef langer dan " + stuckProcessingTimeoutMinutes
                + " minuten in PROCESSING-status zonder afronding (mogelijk gecrashte verwerking)");
            log.warn("[WATCHDOG] messageId={} gemarkeerd als FAILED (laatst bijgewerkt: {})",
                msg.getMessageId(), msg.getUpdatedAt());
        }
        messageRepository.saveAll(stuck);
    }
}
