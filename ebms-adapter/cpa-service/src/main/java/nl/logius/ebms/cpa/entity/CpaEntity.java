package nl.logius.ebms.cpa.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA-entiteit voor de {@code collaboration_protocol_agreement} tabel.
 */
@Entity
@Table(name = "collaboration_protocol_agreement")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cpa_id", nullable = false, unique = true, length = 255)
    private String cpaId;

    @Column(name = "version", length = 50)
    private String version;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "start_date")
    private Instant startDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "cpa_xml", nullable = false, columnDefinition = "TEXT")
    private String cpaXml;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cpaEntity",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<CpaPartyEntity> parties = new ArrayList<>();
}
