package nl.logius.ebms.cpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cpa_outbound_route",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_outbound_action_channel",
           columnNames = {"cpa_id", "from_party_id", "action_binding_id", "channel_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CpaOutboundRouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cpa_id", nullable = false, length = 255)
    private String cpaId;

    @Column(name = "from_party_id", nullable = false, length = 255)
    private String fromPartyId;

    @Column(name = "to_party_id", nullable = false, length = 255)
    private String toPartyId;

    @Column(name = "service", nullable = false, length = 255)
    private String service;

    @Column(name = "service_type", length = 100)
    private String serviceType;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "action_binding_id", nullable = false, length = 255)
    private String actionBindingId;

    @Column(name = "from_role", nullable = false, length = 100)
    private String fromRole;

    @Column(name = "to_role", nullable = false, length = 100)
    private String toRole;

    @Column(name = "signature_required", nullable = false)
    @Builder.Default
    private boolean signatureRequired = false;

    @Column(name = "hash_function", length = 255)
    private String hashFunction;

    @Column(name = "signature_algorithm", length = 255)
    private String signatureAlgorithm;

    @Column(name = "channel_party_id", nullable = false, length = 255)
    private String channelPartyId;

    @Column(name = "channel_id", nullable = false, length = 255)
    private String channelId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}