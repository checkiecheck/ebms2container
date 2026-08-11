package nl.logius.ebms.crypto.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-entiteit voor sleutelmetadata (key_pair_metadata tabel).
 * De sleutels zelf worden NOOIT in de database opgeslagen;
 * ze leven uitsluitend in de PKCS12 KeyStore op het bestandssysteem.
 */
@Entity
@Table(name = "key_pair_metadata")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyPairMetadataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alias", nullable = false, unique = true, length = 255)
    private String alias;

    /** RSA | EC */
    @Column(name = "key_type", nullable = false, length = 50)
    private String keyType;

    /** RSA: 2048/4096 | EC: 256/384/521 */
    @Column(name = "key_size")
    private Integer keySize;

    /** Bijv. RSA-SHA256 of ECDSA-SHA256 */
    @Column(name = "algorithm", nullable = false, length = 100)
    private String algorithm;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /** SHA-256 fingerprint van het certificaat (hex, lowercase). */
    @Column(name = "fingerprint", length = 100)
    private String fingerprint;

    /** SIGNING | ENCRYPTION | SIGNING_ENCRYPTION */
    @Column(name = "key_usage", length = 50)
    private String keyUsage;

    /** ACTIVE | EXPIRED | REVOKED | PENDING_ACTIVATION */
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "superseded_by", length = 255)
    private String supersededBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
