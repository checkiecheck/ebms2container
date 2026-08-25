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
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Toegestane statuswaarden voor de PATCH-status-toggle (Active/Suspend in het dashboard). */
    private static final Set<String> ALLOWED_TOGGLE_STATUSES = Set.of("ACTIVE", "SUSPENDED");

    private final CpaRepository              cpaRepository;
    private final CpaPartyRepository         partyRepository;
    private final CpaDeliveryChannelRepository channelRepository;
    private final PartnerCertificateRepository certRepository;
    private final CpaMapper                  cpaMapper;
    private final CpaPartyXmlParser           partyXmlParser;

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
                "CPA bestaat al: " + dto.getCpaId() + ". Gebruik update (PUT) of verwijder eerst.");
        }
        CpaEntity entity = cpaMapper.toEntity(dto);
        List<PartyInfoDto> parsedParties = partyXmlParser.parseParties(dto.getCpaXml());
        syncParties(entity, parsedParties);

        CpaEntity saved = cpaRepository.save(entity);
        log.info("CPA aangemaakt: {} ({} partij(en) geëxtraheerd uit XML)",
            saved.getCpaId(), parsedParties.size());
        return enrichWithDetails(cpaMapper.toDto(saved), saved);
    }

    /**
     * Volledige overwrite van een bestaande CPA (description, startDate, endDate, status,
     * cpaXml). Gebruikt door het admin-dashboard om een geüploade CPA met een duplicaat-ID
     * te overschrijven i.p.v. te weigeren. Cpa-ID en aanmaakdatum blijven ongewijzigd.
     *
     * <p>De {@code cpa_party}-tabel wordt volledig gesynchroniseerd met de nieuwe {@code cpaXml}:
     * partijen die niet langer in de XML voorkomen worden verwijderd (JPA orphan-removal),
     * bestaande partijen worden bijgewerkt en nieuwe partijen worden toegevoegd.
     */
    @CacheEvict(value = "cpa-by-id", key = "#cpaId")
    @Transactional
    public CpaDto update(String cpaId, CpaDto dto) {
        CpaEntity entity = cpaRepository.findByCpaId(cpaId)
            .orElseThrow(() -> new CpaNotFoundException(cpaId));

        if (dto.getVersion() != null) {
            entity.setVersion(dto.getVersion());
        }
        entity.setDescription(dto.getDescription());
        entity.setStartDate(dto.getStartDate());
        entity.setEndDate(dto.getEndDate());
        entity.setCpaXml(dto.getCpaXml());
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            entity.setStatus(dto.getStatus());
        }

        List<PartyInfoDto> parsedParties = partyXmlParser.parseParties(dto.getCpaXml());
        syncParties(entity, parsedParties);

        CpaEntity saved = cpaRepository.save(entity);
        log.info("CPA overschreven: {} ({} partij(en) gesynchroniseerd uit XML)",
            cpaId, parsedParties.size());
        return enrichWithDetails(cpaMapper.toDto(saved), saved);
    }

    /**
     * Wijzigt uitsluitend de status van een bestaande CPA (bijv. de Active/Suspend-toggle in
     * het admin-dashboard). Toegestane waarden: {@code ACTIVE}, {@code SUSPENDED}.
     */
    @CacheEvict(value = "cpa-by-id", key = "#cpaId")
    @Transactional
    public CpaDto updateStatus(String cpaId, String status) {
        String normalized = status == null ? null : status.trim().toUpperCase();
        if (normalized == null || !ALLOWED_TOGGLE_STATUSES.contains(normalized)) {
            throw new EbmsException("INVALID_STATUS",
                "Status moet ACTIVE of SUSPENDED zijn, ontvangen: " + status);
        }

        CpaEntity entity = cpaRepository.findByCpaId(cpaId)
            .orElseThrow(() -> new CpaNotFoundException(cpaId));
        entity.setStatus(normalized);
        CpaEntity saved = cpaRepository.save(entity);
        log.info("CPA-status gewijzigd: {} -> {}", cpaId, normalized);
        return enrichWithDetails(cpaMapper.toDto(saved), saved);
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

    /**
     * Synchroniseert de {@code parties}-collectie van een CPA-entiteit met de partijen die
     * geëxtraheerd zijn uit de bijbehorende {@code cpaXml}: verwijdert partijen die niet meer
     * voorkomen (JPA orphan-removal bij save), werkt bestaande partijen bij en voegt nieuwe toe.
     * De {@code cpa_id} kolom wordt expliciet gezet (de {@code @ManyToOne}-koppeling zelf is
     * {@code insertable=false, updatable=false}).
     */
    private void syncParties(CpaEntity cpaEntity, List<PartyInfoDto> parsedParties) {
        List<CpaPartyEntity> existing = cpaEntity.getParties();
        Map<String, CpaPartyEntity> existingByPartyId = existing.stream()
            .collect(Collectors.toMap(CpaPartyEntity::getPartyId, p -> p, (a, b) -> a));

        Set<String> parsedPartyIds = parsedParties.stream()
            .map(PartyInfoDto::getPartyId)
            .collect(Collectors.toSet());
        existing.removeIf(p -> !parsedPartyIds.contains(p.getPartyId()));

        for (PartyInfoDto parsed : parsedParties) {
            CpaPartyEntity partyEntity = existingByPartyId.get(parsed.getPartyId());
            if (partyEntity != null) {
                partyEntity.setPartyIdType(parsed.getPartyIdType());
                partyEntity.setOin(parsed.getOin());
                partyEntity.setOinValidated(parsed.isOinValidated());
                partyEntity.setRole(parsed.getRole());
                partyEntity.setService(parsed.getService());
            } else {
                existing.add(CpaPartyEntity.builder()
                    .cpaEntity(cpaEntity)
                    .cpaId(cpaEntity.getCpaId())
                    .partyId(parsed.getPartyId())
                    .partyIdType(parsed.getPartyIdType())
                    .oin(parsed.getOin())
                    .oinValidated(parsed.isOinValidated())
                    .role(parsed.getRole())
                    .service(parsed.getService())
                    .build());
            }
        }
    }

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
