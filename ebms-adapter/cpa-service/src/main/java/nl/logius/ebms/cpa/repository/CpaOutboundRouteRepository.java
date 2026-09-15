package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CpaOutboundRouteRepository extends JpaRepository<CpaOutboundRouteEntity, UUID> {

    List<CpaOutboundRouteEntity> findByCpaId(String cpaId);

    List<CpaOutboundRouteEntity> findByCpaIdAndFromPartyIdAndToPartyId(
        String cpaId, String fromPartyId, String toPartyId);
}