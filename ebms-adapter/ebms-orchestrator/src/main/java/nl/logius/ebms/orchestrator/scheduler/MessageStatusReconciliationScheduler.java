package nl.logius.ebms.orchestrator.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
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
 * retry-scheduler, die uitsluitend OUTBOUND berichten met status {@code FAILED} oppikt.
 *
 * <p>OUTBOUND en INBOUND krijgen een aparte threshold: een OUTBOUND bericht dat na een paar
 * minuten nog op PROCESSING staat wijst vrijwel zeker op een crash tijdens verzending, maar een
 * INBOUND bericht kan legitiem langer op PROCESSING blijven staan in afwachting van een
 * downstream-consument op de {@code ebms.inbound.messages} queue - vandaar een ruimere,
 * apart instelbare timeout voor INBOUND.
 *
 * <p>Configuratie ({@code application.yml} prefix {@code ebms.watchdog}):
 * <ul>
 *   <li>{@code stuck-processing-timeout-minutes} – OUTBOUND-threshold in minuten (standaard 5)</li>
 *   <li>{@code inbound-stuck-processing-timeout-minutes} – INBOUND-threshold (standaard 30)</li>
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

    @Value("${ebms.watchdog.inbound-stuck-processing-timeout-minutes:30}")
    private int inboundStuckProcessingTimeoutMinutes;

    /**
     * Controleert beide richtingen (OUTBOUND met de korte, INBOUND met de lange threshold) en
     * markeert berichten die te lang op PROCESSING vaststaan als FAILED met een verklarende
     * foutmelding.
     */
    @Scheduled(fixedDelayString = "${ebms.watchdog.check-interval-ms:300000}")
    @Transactional
    public void reconcileStuckProcessingMessages() {
        reconcileDirection(MessageDirection.OUTBOUND, stuckProcessingTimeoutMinutes);
        reconcileDirection(MessageDirection.INBOUND, inboundStuckProcessingTimeoutMinutes);
    }

    private void reconcileDirection(MessageDirection direction, int timeoutMinutes) {
        Instant threshold = Instant.now().minusSeconds(timeoutMinutes * 60L);

        List<EbmsMessageEntity> stuck = messageRepository.findStuckProcessingMessages(direction, threshold);
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("[WATCHDOG] {} {}-bericht(en) vastgeplakt op PROCESSING (> {} min) - markeren als FAILED",
            stuck.size(), direction, timeoutMinutes);

        String reasonSuffix = direction == MessageDirection.INBOUND
            ? " (mogelijk geen downstream-consument op de ebms.inbound.messages queue)"
            : " (mogelijk gecrashte verwerking)";

        for (EbmsMessageEntity msg : stuck) {
            msg.setStatus(MessageStatus.FAILED);
            msg.setErrorMessage("Watchdog: " + direction + "-bericht bleef langer dan " + timeoutMinutes
                + " minuten in PROCESSING-status zonder afronding" + reasonSuffix);
            log.warn("[WATCHDOG] messageId={} gemarkeerd als FAILED (laatst bijgewerkt: {})",
                msg.getMessageId(), msg.getUpdatedAt());
        }
        messageRepository.saveAll(stuck);
    }
}
