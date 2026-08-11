package nl.logius.ebms.cpa.repository;

import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA-repository voor de {@code cpa_delivery_channel} tabel.
 */
@Repository
public interface CpaDeliveryChannelRepository
    extends JpaRepository<CpaDeliveryChannelEntity, UUID> {

    /** Alle kanalen voor een specifieke CPA. */
    List<CpaDeliveryChannelEntity> findByCpaId(String cpaId);

    /** Eerste kanaal voor een specifieke CPA en partij-ID. */
    Optional<CpaDeliveryChannelEntity> findFirstByCpaIdAndPartyId(String cpaId, String partyId);

    /** Kanaal op CPA + partij + channel-ID. */
    Optional<CpaDeliveryChannelEntity> findByCpaIdAndPartyIdAndChannelId(
        String cpaId, String partyId, String channelId);
}
