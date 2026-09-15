package nl.logius.ebms.cpa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.CpaPartyEntity;
import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaOutboundRouteRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
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
    private final CpaOutboundRouteRepository routeRepository;
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

    @Transactional
    public OutboundRouteDto findOutboundRoute(String cpaId, String fromPartyId,
            String toPartyId, String service, String serviceType, String action,
            String fromRole, String toRole) {
        requireRouteValue("fromPartyId", fromPartyId);
        requireRouteValue("toPartyId", toPartyId);
        requireRouteValue("service", service);
        requireRouteValue("action", action);

        List<CpaOutboundRouteEntity> matches = findMatchingRoutes(
            cpaId, fromPartyId, toPartyId, service, serviceType, action, fromRole, toRole);
        if (matches.isEmpty() && routeRepository.findByCpaId(cpaId).isEmpty()) {
            CpaEntity cpa = cpaRepository.findByCpaIdForUpdate(cpaId)
                .orElseThrow(() -> new CpaNotFoundException(cpaId));
            List<CpaOutboundRouteEntity> existingRoutes = routeRepository.findByCpaId(cpaId);
            if (existingRoutes.isEmpty()) {
                List<CpaOutboundRouteEntity> parsed = partyXmlParser.parseOutboundRoutes(
                    cpa.getCpaXml(), cpaId);
                if (!parsed.isEmpty()) {
                    routeRepository.saveAll(parsed);
                    existingRoutes = parsed;
                }
            }
            matches = existingRoutes.stream()
                .filter(route -> routeMatches(
                    route, fromPartyId, toPartyId, service, serviceType, action, fromRole, toRole))
                .toList();
        }
        if (matches.isEmpty()) {
            throw new EbmsException("ROUTE_NOT_FOUND",
                "Geen outbound CPA-route gevonden voor CPA=" + cpaId
                    + ", fromPartyId=" + fromPartyId + ", toPartyId=" + toPartyId
                    + ", service=" + service + ", action=" + action);
        }
        if (matches.size() > 1) {
            throw new EbmsException("ROUTE_AMBIGUOUS",
                "Meerdere outbound CPA-routes gevonden voor CPA=" + cpaId
                    + ", fromPartyId=" + fromPartyId + ", toPartyId=" + toPartyId
                    + ", service=" + service + ", action=" + action);
        }

        CpaOutboundRouteEntity route = matches.get(0);
        CpaDeliveryChannelEntity channel = channelRepository
            .findByCpaIdAndPartyIdAndChannelId(
                cpaId, route.getChannelPartyId(), route.getChannelId())
            .orElseThrow(() -> new EbmsException("CHANNEL_NOT_FOUND",
                "Kanaal " + route.getChannelId() + " van outbound CPA-route niet gevonden"));
        return OutboundRouteDto.builder()
            .cpaId(cpaId)
            .fromPartyId(route.getFromPartyId())
            .toPartyId(route.getToPartyId())
            .service(route.getService())
            .serviceType(route.getServiceType())
            .action(route.getAction())
            .fromRole(route.getFromRole())
            .toRole(route.getToRole())
            .channel(cpaMapper.toChannelDto(channel))
            .build();
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
            .syncReplyMode(dto.getSyncReplyMode())
            .build();
        return cpaMapper.toChannelDto(channelRepository.save(entity));
    }

    // ── Schrijf-operaties ─────────────────────────────────────────────────

    @Transactional
    public CpaDto create(CpaDto dto) {
        String cpaId = partyXmlParser.parseCpaId(dto.getCpaXml());
        if (cpaId == null || cpaId.isBlank()) {
            throw new EbmsException("INVALID_CPA", "CPA XML bevat geen geldige cpaId.");
        }
        if (cpaRepository.existsByCpaId(cpaId)) {
            throw new EbmsException("CPA_ALREADY_EXISTS",
                "CPA bestaat al: " + cpaId + ". Gebruik update (PUT) of verwijder eerst.");
        }

        CpaEntity entity = cpaMapper.toEntity(dto);
        entity.setCpaId(cpaId); // Set the parsed cpaId

        // Parse and handle dates from XML
        Instant startDate = partyXmlParser.parseStartDate(dto.getCpaXml());
        Instant endDate = partyXmlParser.parseEndDate(dto.getCpaXml());

        if (startDate != null) {
            entity.setStartDate(startDate);
        }
        if (endDate != null) {
            entity.setEndDate(endDate);
        }

        // Optional: Log warning for outdated dates
        if (entity.getEndDate() != null && entity.getEndDate().isBefore(Instant.now())) {
            log.warn("CPA {} heeft een einddatum in het verleden: {}. Overweeg deze te verlengen.",
                cpaId, entity.getEndDate());
        }

        List<PartyInfoDto> parsedParties = partyXmlParser.parseParties(dto.getCpaXml());
        syncParties(entity, parsedParties);

        CpaEntity saved = cpaRepository.save(entity);
        syncCertificates(cpaId, dto.getCpaXml());
        syncDeliveryChannels(cpaId, dto.getCpaXml());
        syncOutboundRoutes(cpaId, dto.getCpaXml());
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
     * bestaande partijen worden bijgewerkt en nieuwe partijen worden toegevoegd. Idem voor
     * {@code partner_certificate}: de XML is de single source of truth, dus handmatig via
     * {@link #addCertificate} toegevoegde certificaten die niet (meer) in de XML voorkomen
     * worden bij de volgende create/update verwijderd.
     */
    @CacheEvict(value = "cpa-by-id", key = "#result.cpaId")
    @Transactional
    public CpaDto update(String cpaIdFromPath, CpaDto dto) {
        String cpaIdFromXml = partyXmlParser.parseCpaId(dto.getCpaXml());
        if (cpaIdFromXml == null || cpaIdFromXml.isBlank()) {
            throw new EbmsException("INVALID_CPA", "CPA XML bevat geen geldige cpaId.");
        }
        if (!cpaIdFromPath.equals(cpaIdFromXml)) {
            log.warn("CPA ID in pad ({}) komt niet overeen met CPA ID in XML ({}). Gebruik CPA ID uit XML.",
                cpaIdFromPath, cpaIdFromXml);
        }

        CpaEntity entity = cpaRepository.findByCpaId(cpaIdFromXml)
            .orElseThrow(() -> new CpaNotFoundException(cpaIdFromXml));

        if (dto.getVersion() != null) {
            entity.setVersion(dto.getVersion());
        }
        entity.setDescription(dto.getDescription());
        // Parse and handle dates from XML
        Instant startDate = partyXmlParser.parseStartDate(dto.getCpaXml());
        Instant endDate = partyXmlParser.parseEndDate(dto.getCpaXml());

        if (startDate != null) {
            entity.setStartDate(startDate);
        }
        if (endDate != null) {
            entity.setEndDate(endDate);
        }

        // Optional: Log warning for outdated dates
        if (entity.getEndDate() != null && entity.getEndDate().isBefore(Instant.now())) {
            log.warn("CPA {} heeft een einddatum in het verleden: {}. Overweeg deze te verlengen.",
                cpaIdFromXml, entity.getEndDate());
        }

        entity.setCpaXml(dto.getCpaXml());
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            entity.setStatus(dto.getStatus());
        }

        List<PartyInfoDto> parsedParties = partyXmlParser.parseParties(dto.getCpaXml());
        syncParties(entity, parsedParties);

        CpaEntity saved = cpaRepository.save(entity);
        syncCertificates(cpaIdFromXml, dto.getCpaXml());
        syncDeliveryChannels(cpaIdFromXml, dto.getCpaXml());
        syncOutboundRoutes(cpaIdFromXml, dto.getCpaXml());
        log.info("CPA overschreven: {} ({} partij(en) gesynchroniseerd uit XML)",
            cpaIdFromXml, parsedParties.size());
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

    /**
     * Voegt handmatig een certificaat toe aan een CPA-partner.
     *
     * <p><b>Let op:</b> als de CPA-XML zelf ingesloten {@code <Certificate>}-elementen bevat,
     * geldt de XML als single source of truth - een handmatig toegevoegd certificaat dat niet
     * (ook) in de XML voorkomt, wordt bij de volgende {@code create}/{@code update} van deze
     * CPA verwijderd door {@link #syncCertificates}.
     */
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

    /**
     * Synchroniseert de {@code partner_certificate}-rijen van een CPA met de certificaten die
     * ingesloten zijn in de bijbehorende {@code cpaXml}. De XML is single source of truth:
     * certificaten die niet (meer) in de XML voorkomen worden verwijderd (ook als ze handmatig
     * via {@link #addCertificate} zijn toegevoegd), bestaande worden bijgewerkt en nieuwe
     * worden toegevoegd. Natuurlijke sleutel: {@code (partyId, certificateAlias)}.
     */
    private void syncCertificates(String cpaId, String cpaXml) {
        List<PartnerCertificateEntity> parsed = partyXmlParser.parseCertificates(cpaXml, cpaId);
        List<PartnerCertificateEntity> existing = certRepository.findByCpaId(cpaId);

        Map<String, PartnerCertificateEntity> existingByKey = existing.stream()
            .collect(Collectors.toMap(this::certKey, e -> e, (a, b) -> a));
        Set<String> parsedKeys = parsed.stream().map(this::certKey).collect(Collectors.toSet());

        List<PartnerCertificateEntity> toDelete = existing.stream()
            .filter(e -> !parsedKeys.contains(certKey(e)))
            .toList();
        if (!toDelete.isEmpty()) {
            certRepository.deleteAll(toDelete);
        }

        List<PartnerCertificateEntity> toSave = new ArrayList<>();
        for (PartnerCertificateEntity p : parsed) {
            PartnerCertificateEntity existingCert = existingByKey.get(certKey(p));
            if (existingCert != null) {
                existingCert.setCertificatePem(p.getCertificatePem());
                existingCert.setValidFrom(p.getValidFrom());
                existingCert.setValidUntil(p.getValidUntil());
                existingCert.setCertificateUsage(p.getCertificateUsage());
                toSave.add(existingCert);
            } else {
                toSave.add(p);
            }
        }
        if (!toSave.isEmpty()) {
            certRepository.saveAll(toSave);
        }
    }

    private String certKey(PartnerCertificateEntity cert) {
        return cert.getPartyId() + "::" + cert.getCertificateAlias();
    }

    /**
     * Synchroniseert de {@code cpa_delivery_channel}-rijen van een CPA met de afleverkanalen die
     * gedefinieerd zijn in de bijbehorende {@code cpaXml} ({@code <DeliveryChannel>} +
     * gekoppelde {@code <Transport>}/{@code <DocExchange>}). De XML is single source of truth:
     * kanalen die niet (meer) in de XML voorkomen worden verwijderd (ook als ze handmatig via
     * {@link #addDeliveryChannel} zijn toegevoegd), bestaande worden bijgewerkt en nieuwe worden
     * toegevoegd. Natuurlijke sleutel: {@code (partyId, channelId)}.
     */
    private void syncDeliveryChannels(String cpaId, String cpaXml) {
        List<CpaDeliveryChannelEntity> parsed = partyXmlParser.parseDeliveryChannels(cpaXml, cpaId);
        List<CpaDeliveryChannelEntity> existing = channelRepository.findByCpaId(cpaId);

        Map<String, CpaDeliveryChannelEntity> existingByKey = existing.stream()
            .collect(Collectors.toMap(this::channelKey, e -> e, (a, b) -> a));
        Set<String> parsedKeys = parsed.stream().map(this::channelKey).collect(Collectors.toSet());

        List<CpaDeliveryChannelEntity> toDelete = existing.stream()
            .filter(e -> !parsedKeys.contains(channelKey(e)))
            .toList();
        if (!toDelete.isEmpty()) {
            channelRepository.deleteAll(toDelete);
        }

        List<CpaDeliveryChannelEntity> toSave = new ArrayList<>();
        for (CpaDeliveryChannelEntity p : parsed) {
            CpaDeliveryChannelEntity existingChannel = existingByKey.get(channelKey(p));
            if (existingChannel != null) {
                existingChannel.setDkProfile(p.getDkProfile());
                existingChannel.setTransportProtocol(p.getTransportProtocol());
                existingChannel.setEndpointUrl(p.getEndpointUrl());
                existingChannel.setRetryCount(p.getRetryCount());
                existingChannel.setRetryInterval(p.getRetryInterval());
                existingChannel.setPersistDuration(p.getPersistDuration());
                toSave.add(existingChannel);
            } else {
                toSave.add(p);
            }
        }
        if (!toSave.isEmpty()) {
            channelRepository.saveAll(toSave);
        }
    }

    private String channelKey(CpaDeliveryChannelEntity e) {
        return e.getPartyId() + "::" + e.getChannelId();
    }

    private void syncOutboundRoutes(String cpaId, String cpaXml) {
        List<CpaOutboundRouteEntity> parsed = partyXmlParser.parseOutboundRoutes(cpaXml, cpaId);
        List<CpaOutboundRouteEntity> existing = routeRepository.findByCpaId(cpaId);
        if (!existing.isEmpty()) {
            routeRepository.deleteAllInBatch(existing);
            routeRepository.flush();
        }
        if (!parsed.isEmpty()) {
            routeRepository.saveAll(parsed);
        }
    }

    private List<CpaOutboundRouteEntity> findMatchingRoutes(String cpaId, String fromPartyId,
            String toPartyId, String service, String serviceType, String action,
            String fromRole, String toRole) {
        return routeRepository.findByCpaIdAndFromPartyIdAndToPartyId(
                cpaId, fromPartyId, toPartyId).stream()
            .filter(route -> routeMatches(
                route, fromPartyId, toPartyId, service, serviceType, action, fromRole, toRole))
            .toList();
    }

    private boolean routeMatches(CpaOutboundRouteEntity route, String fromPartyId,
            String toPartyId, String service, String serviceType, String action,
            String fromRole, String toRole) {
        return fromPartyId.equals(route.getFromPartyId())
            && toPartyId.equals(route.getToPartyId())
            && service.equals(route.getService())
            && (serviceType == null || serviceType.isBlank()
                ? route.getServiceType() == null || route.getServiceType().isBlank()
                : serviceType.equals(route.getServiceType()))
            && action.equals(route.getAction())
            && (fromRole == null || fromRole.isBlank() || fromRole.equals(route.getFromRole()))
            && (toRole == null || toRole.isBlank() || toRole.equals(route.getToRole()));
    }

    private void requireRouteValue(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new EbmsException("INVALID_ROUTE_REQUEST", name + " ontbreekt voor CPA-route lookup");
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
