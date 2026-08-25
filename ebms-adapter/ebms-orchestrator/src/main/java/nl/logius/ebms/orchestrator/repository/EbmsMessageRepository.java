package nl.logius.ebms.orchestrator.repository;

import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EbmsMessageRepository extends JpaRepository<EbmsMessageEntity, UUID> {

    /** Duplicate suppression: bestaat het bericht al in de database? */
    boolean existsByMessageId(String messageId);

    Optional<EbmsMessageEntity> findByMessageId(String messageId);

    /** Zoek het originele bericht op status, gebruikt voor ACK-afhandeling. */
    Optional<EbmsMessageEntity> findByMessageIdAndStatus(String messageId, MessageStatus status);

    List<EbmsMessageEntity> findByConversationId(String conversationId);

    List<EbmsMessageEntity> findByStatus(MessageStatus status);

    /** Admin message-monitor: optioneel filteren op richting (INBOUND/OUTBOUND). */
    Page<EbmsMessageEntity> findByDirection(MessageDirection direction, Pageable pageable);

    /** Berichten die opnieuw geprobeerd moeten worden (rm-profielen). */
    @Query("""
        SELECT m FROM EbmsMessageEntity m
        WHERE m.status = 'FAILED'
          AND m.retryCount < :maxRetries
          AND (m.lastRetryAt IS NULL OR m.lastRetryAt < :retryBefore)
        """)
    List<EbmsMessageEntity> findMessagesForRetry(
        @Param("maxRetries") int maxRetries,
        @Param("retryBefore") Instant retryBefore);

    /** Berichten waarvan de time-to-live verstreken is. */
    @Query("""
        SELECT m FROM EbmsMessageEntity m
        WHERE m.timeToLive IS NOT NULL
          AND m.timeToLive < :now
          AND m.status NOT IN ('DELIVERED', 'ACKNOWLEDGED', 'FAILED', 'DUPLICATE')
        """)
    List<EbmsMessageEntity> findExpiredMessages(@Param("now") Instant now);

    /**
     * Watchdog: berichten die langer dan de threshold vaststaan op PROCESSING (bijv. door een
     * gecrashte verwerkingsthread of ontbrekende AMQP-ack), gebruikt door
     * {@code MessageStatusReconciliationScheduler}. {@code updatedAt} wordt gebruikt (niet het
     * ebXML {@code timestamp}-veld) omdat dat betrouwbaar het moment markeert waarop de rij
     * voor het laatst is bijgewerkt (incl. de overgang naar PROCESSING).
     */
    @Query("""
        SELECT m FROM EbmsMessageEntity m
        WHERE m.status = 'PROCESSING'
          AND m.updatedAt < :threshold
        """)
    List<EbmsMessageEntity> findStuckProcessingMessages(@Param("threshold") Instant threshold);
}
