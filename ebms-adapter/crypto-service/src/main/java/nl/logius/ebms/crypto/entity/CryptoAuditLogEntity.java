package nl.logius.ebms.crypto.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor append-only crypto-auditlogs (crypto_audit_log tabel).
 * Bevat een volledig auditspoor van alle XML-DSig/Enc operaties.
 */
@Entity
@Table(name = "crypto_audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Operatietype:
     * XML_SIGN | XML_VERIFY | XML_ENCRYPT | XML_DECRYPT | C14N | KEY_LOAD
     */
    @Column(name = "operation", nullable = false, length = 100)
    private String operation;

    @Column(name = "key_alias", length = 255)
    private String keyAlias;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "cpa_id", length = 255)
    private String cpaId;

    @Column(name = "party_id", length = 255)
    private String partyId;

    /** SUCCESS | FAILURE */
    @Column(name = "result", nullable = false, length = 50)
    private String result;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    /** Uitvoeringstijd in milliseconden (performance-monitoring). */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @CreationTimestamp
    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;
}
