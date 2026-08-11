package nl.logius.ebms.cpa.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor de {@code partner_certificate} tabel.
 * Bewaart PKI-certificaten (X.509/PEM) per CPA-partner voor XML-DSig-verificatie.
 */
@Entity
@Table(name = "partner_certificate",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_cert_alias",
           columnNames = {"cpa_id", "party_id", "certificate_alias"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerCertificateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cpa_id", nullable = false, length = 255)
    private String cpaId;

    @Column(name = "party_id", nullable = false, length = 255)
    private String partyId;

    @Column(name = "certificate_alias", nullable = false, length = 255)
    private String certificateAlias;

    @Column(name = "certificate_pem", nullable = false, columnDefinition = "TEXT")
    private String certificatePem;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /**
     * Gebruik: SIGNING | ENCRYPTION | SIGNING_ENCRYPTION
     */
    @Column(name = "certificate_usage", length = 50)
    private String certificateUsage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
