package nl.logius.ebms.cpa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.CpaPartyEntity;
import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Businesslogica voor CPA-beheer en -opzoekingen.
 *
 * <p>Resultaten worden gecached via Caffeine (configuratie in {@code application.yml}).
 * Cache-evictie vindt plaats bij aanmaken of verwijderen van een CPA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CpaService {

    private final CpaRepository              cpaRepository;
    private final CpaPartyRepository         partyRepository;
    private final CpaDeliveryChannelRepository channelRepository;
    private final PartnerCertificateRepository certRepository;
    private final CpaMapper                  cpaMapper;

    // ── Lees-operaties ────────────────────────────────────────────────────

    @Cacheable(value = "cpa-by-id", key = "#cpaId")
    @Transactional(readOnly = true)
    public CpaDto findByCpaId(String cpaId) {
        log.debug("CPA opzoeken: {}", cpaId);
        CpaEntity entity = cpaRepository.findByCpaId(cpaId)
            .orElseThrow(() -> new CpaNotFoundException(cpaId));
        return enrichWithDetails(cpaMapper.toDto(entity), entity);
    }

    @Transactional(readOnly = true)
    public List<CpaDto> findAll() {
        return cpaMapper.toDtoList(cpaRepository.findAll());
    }

    @Transactional(readOnly = true)
    public List<PartyInfoDto> findPartiesByCpaId(String cpaId) {
        return partyRepository.findByCpaId(cpaId).stream()
            .map(cpaMapper::toPartyDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PartyInfoDto> findPartiesByOin(String oin) {
        return partyRepository.findByOin(oin).stream()
            .map(cpaMapper::toPartyDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PartnerCertificateEntity> findValidCertificates(String cpaId, String partyId) {
        return certRepository.findByCpaIdAndPartyIdAndValidUntilAfter(cpaId, partyId, Instant.now());
    }

    /**
     * Haalt het afleverkanaal op voor een specifieke CPA-partij (gebruikt door de orchestrator
     * om het endpoint-URL en DK-profiel voor uitgaande berichten te bepalen).
     */
    @Cacheable(value = "channel-by-party", key = "#cpaId + ':' + #partyId")
    @Transactional(readOnly = true)
    public DeliveryChannelDto findDeliveryChannel(String cpaId, String partyId) {
        CpaDeliveryChannelEntity entity = channelRepository
            .findFirstByCpaIdAndPartyId(cpaId, partyId)
            .orElseThrow(() -> new EbmsException("CHANNEL_NOT_FOUND",
                "Geen afleverkanaal gevonden voor CPA=" + cpaId + " partyId=" + partyId));
        return cpaMapper.toChannelDto(entity);
    }

    /**
     * Alle afleverkanalen voor een CPA.
     */
    @Transactional(readOnly = true)
    public List<DeliveryChannelDto> findDeliveryChannels(String cpaId) {
        return cpaMapper.toChannelDtoList(channelRepository.findByCpaId(cpaId));
    }

    /**
     * Voegt een nieuw afleverkanaal toe aan een CPA.
     */
    @Transactional
    public DeliveryChannelDto addDeliveryChannel(String cpaId, DeliveryChannelDto dto) {
        if (!cpaRepository.existsByCpaId(cpaId)) {
            throw new CpaNotFoundException(cpaId);
        }
        CpaDeliveryChannelEntity entity = CpaDeliveryChannelEntity.builder()
            .cpaId(cpaId)
            .partyId(dto.getPartyId())
            .channelId(dto.getChannelId())
            .dkProfile(dto.getDkProfile())
            .transportProtocol(dto.getTransportProtocol() != null ? dto.getTransportProtocol() : "HTTP")
            .endpointUrl(dto.getEndpointUrl())
            .retryCount(dto.getRetryCount())
            .retryInterval(dto.getRetryInterval())
            .persistDuration(dto.getPersistDuration())
            .build();
        return cpaMapper.toChannelDto(channelRepository.save(entity));
    }

    // ── Schrijf-operaties ─────────────────────────────────────────────────

    @Transactional
    public CpaDto create(CpaDto dto) {
        if (cpaRepository.existsByCpaId(dto.getCpaId())) {
            throw new EbmsException("CPA_ALREADY_EXISTS",
                "CPA bestaat al: " + dto.getCpaId() + ". Gebruik update of verwijder eerst.");
        }
        CpaEntity entity = cpaMapper.toEntity(dto);
        CpaEntity saved = cpaRepository.save(entity);
        log.info("CPA aangemaakt: {}", saved.getCpaId());
        return cpaMapper.toDto(saved);
    }

    @CacheEvict(value = "cpa-by-id", key = "#cpaId")
    @Transactional
    public void deleteByCpaId(String cpaId) {
        if (!cpaRepository.existsByCpaId(cpaId)) {
            throw new CpaNotFoundException(cpaId);
        }
        cpaRepository.deleteByCpaId(cpaId);
        log.info("CPA verwijderd: {}", cpaId);
    }

    @Transactional
    public PartnerCertificateEntity addCertificate(PartnerCertificateEntity cert) {
        if (!cpaRepository.existsByCpaId(cert.getCpaId())) {
            throw new CpaNotFoundException(cert.getCpaId());
        }
        PartnerCertificateEntity saved = certRepository.save(cert);
        log.info("Certificaat toegevoegd voor CPA {} party {}: {}",
            cert.getCpaId(), cert.getPartyId(), cert.getCertificateAlias());
        return saved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private CpaDto enrichWithDetails(CpaDto dto, CpaEntity entity) {
        List<PartyInfoDto> parties = entity.getParties().stream()
            .map(cpaMapper::toPartyDto)
            .toList();
        return CpaDto.builder()
            .id(dto.getId())
            .cpaId(dto.getCpaId())
            .version(dto.getVersion())
            .description(dto.getDescription())
            .startDate(dto.getStartDate())
            .endDate(dto.getEndDate())
            .status(dto.getStatus())
            .cpaXml(dto.getCpaXml())
            .createdAt(dto.getCreatedAt())
            .updatedAt(dto.getUpdatedAt())
            .parties(parties)
            .build();
    }
}
