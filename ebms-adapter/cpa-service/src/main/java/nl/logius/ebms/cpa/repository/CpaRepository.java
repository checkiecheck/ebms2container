package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.CpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CpaRepository extends JpaRepository<CpaEntity, UUID> {

    Optional<CpaEntity> findByCpaId(String cpaId);

    boolean existsByCpaId(String cpaId);

    @Transactional
    void deleteByCpaId(String cpaId);
}
