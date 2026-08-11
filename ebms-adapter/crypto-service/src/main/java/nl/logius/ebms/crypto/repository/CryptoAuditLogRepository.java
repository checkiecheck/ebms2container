package nl.logius.ebms.crypto.repository;

import nl.logius.ebms.crypto.entity.CryptoAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CryptoAuditLogRepository extends JpaRepository<CryptoAuditLogEntity, UUID> {

    Page<CryptoAuditLogEntity> findByMessageIdOrderByPerformedAtDesc(String messageId, Pageable pageable);

    Page<CryptoAuditLogEntity> findByResultOrderByPerformedAtDesc(String result, Pageable pageable);
}
