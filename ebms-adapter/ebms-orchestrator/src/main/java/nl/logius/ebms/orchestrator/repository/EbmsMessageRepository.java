package nl.logius.ebms.orchestrator.repository;

import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
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

    List<EbmsMessageEntity> findByConversationId(String conversationId);

    List<EbmsMessageEntity> findByStatus(MessageStatus status);

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
          AND m.status NOT IN ('DELIVERED', 'ACKNOWLEDGED', 'DUPLICATE')
        """)
    List<EbmsMessageEntity> findExpiredMessages(@Param("now") Instant now);
}
