package nl.logius.ebms.crypto.repository;

import nl.logius.ebms.crypto.entity.KeyPairMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KeyPairMetadataRepository extends JpaRepository<KeyPairMetadataEntity, UUID> {

    Optional<KeyPairMetadataEntity> findByAlias(String alias);

    List<KeyPairMetadataEntity> findByStatus(String status);

    boolean existsByAlias(String alias);
}
