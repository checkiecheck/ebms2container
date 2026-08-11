package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PartnerCertificateRepository extends JpaRepository<PartnerCertificateEntity, UUID> {

    List<PartnerCertificateEntity> findByCpaIdAndPartyId(String cpaId, String partyId);

    /** Geldig op een bepaald tijdstip (tbv certificaatrotatie). */
    List<PartnerCertificateEntity> findByCpaIdAndPartyIdAndValidUntilAfter(
        String cpaId, String partyId, Instant now);
}
