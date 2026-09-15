package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.CpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CpaRepository extends JpaRepository<CpaEntity, UUID> {

    Optional<CpaEntity> findByCpaId(String cpaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CpaEntity c where c.cpaId = :cpaId")
    Optional<CpaEntity> findByCpaIdForUpdate(@Param("cpaId") String cpaId);

    boolean existsByCpaId(String cpaId);

    @Transactional
    void deleteByCpaId(String cpaId);
}
