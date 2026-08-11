package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.CpaPartyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CpaPartyRepository extends JpaRepository<CpaPartyEntity, UUID> {

    List<CpaPartyEntity> findByCpaId(String cpaId);

    /** Opzoeken via OIN (tbv mTLS-validatie). */
    List<CpaPartyEntity> findByOin(String oin);
}
