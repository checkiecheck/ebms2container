package nl.logius.ebms.cpa.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor de {@code cpa_delivery_channel} tabel.
 *
 * <p>Bevat het technisch afleverkanaal per CPA-partij conform ebXML CPPA 2.0.
 * Bevat het Digikoppeling-profiel, endpoint-URL en Reliable Messaging parameters.
 */
@Entity
@Table(name = "cpa_delivery_channel",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_channel",
           columnNames = {"cpa_id", "party_id", "channel_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CpaDeliveryChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cpa_id", nullable = false, length = 255)
    private String cpaId;

    @Column(name = "party_id", nullable = false, length = 255)
    private String partyId;

    @Column(name = "channel_id", nullable = false, length = 255)
    private String channelId;

    /**
     * Digikoppeling-profiel code: osb-be | osb-rm | osb-be-s | osb-rm-s | osb-be-e | osb-rm-e.
     */
    @Column(name = "dk_profile", nullable = false, length = 50)
    private String dkProfile;

    @Column(name = "transport_protocol", length = 50)
    @Builder.Default
    private String transportProtocol = "HTTP";

    /** HTTPS-endpoint URL van de ketenpartner. */
    @Column(name = "endpoint_url", length = 500)
    private String endpointUrl;

    /** Maximaal aantal retries (alleen bij rm-profielen). */
    @Column(name = "retry_count")
    private Integer retryCount;

    /** Interval tussen retries in seconden. */
    @Column(name = "retry_interval")
    private Integer retryInterval;

    /** MessageExpiry conform ebXML persistDuration, in seconden. */
    @Column(name = "persist_duration")
    private Integer persistDuration;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
