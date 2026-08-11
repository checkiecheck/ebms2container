package nl.logius.ebms.cpa.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor de {@code cpa_party} tabel.
 */
@Entity
@Table(name = "cpa_party",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_cpa_party",
           columnNames = {"cpa_id", "party_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CpaPartyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cpa_id", referencedColumnName = "cpa_id",
                insertable = false, updatable = false)
    private CpaEntity cpaEntity;

    @Column(name = "cpa_id", nullable = false, length = 255)
    private String cpaId;

    @Column(name = "party_id", nullable = false, length = 255)
    private String partyId;

    @Column(name = "party_id_type", length = 100)
    private String partyIdType;

    /** Organisatie Identificatie Nummer (ISO 6523, 20 cijfers). */
    @Column(name = "oin", length = 20)
    private String oin;

    @Column(name = "oin_validated", nullable = false)
    @Builder.Default
    private boolean oinValidated = false;

    @Column(name = "role", length = 100)
    private String role;

    @Column(name = "service", length = 255)
    private String service;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
