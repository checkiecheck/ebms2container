package nl.logius.ebms.orchestrator.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor de {@code ebms_message} tabel.
 * Slaat de volledige bericht-state op conform ebXML Reliable Messaging.
 */
@Entity
@Table(name = "ebms_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EbmsMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── ebXML MessageHeader velden ────────────────────────────────────────

    @Column(name = "message_id", nullable = false, unique = true, length = 255)
    private String messageId;

    @Column(name = "ref_to_message_id", length = 255)
    private String refToMessageId;

    @Column(name = "conversation_id", nullable = false, length = 255)
    private String conversationId;

    @Column(name = "cpa_id", nullable = false, length = 255)
    private String cpaId;

    // ── Partij-informatie ─────────────────────────────────────────────────

    @Column(name = "from_party_id", nullable = false, length = 255)
    private String fromPartyId;

    @Column(name = "from_party_type", length = 100)
    private String fromPartyType;

    @Column(name = "from_role", length = 100)
    private String fromRole;

    @Column(name = "to_party_id", nullable = false, length = 255)
    private String toPartyId;

    @Column(name = "to_party_type", length = 100)
    private String toPartyType;

    @Column(name = "to_role", length = 100)
    private String toRole;

    // ── Service / actie ───────────────────────────────────────────────────

    @Column(name = "service", nullable = false, length = 255)
    private String service;

    @Column(name = "service_type", length = 100)
    private String serviceType;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    // ── Metadata ──────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    @Builder.Default
    private MessageDirection direction = MessageDirection.INBOUND;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private MessageStatus status = MessageStatus.RECEIVED;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "time_to_live")
    private Instant timeToLive;

    // ── Reliable Messaging ────────────────────────────────────────────────

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private short retryCount = 0;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "ack_requested", nullable = false)
    @Builder.Default
    private boolean ackRequested = false;

    @Column(name = "duplicate_elimination", nullable = false)
    @Builder.Default
    private boolean duplicateElimination = true;

    // ── Payload ───────────────────────────────────────────────────────────

    @Column(name = "payload_content_type", length = 255)
    private String payloadContentType;

    @Column(name = "payload_ref", length = 500)
    private String payloadRef;

    @Column(name = "raw_soap_xml", columnDefinition = "TEXT")
    private String rawSoapXml;

    // ── Audit ─────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
