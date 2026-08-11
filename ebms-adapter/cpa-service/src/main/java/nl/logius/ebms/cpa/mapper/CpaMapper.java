package nl.logius.ebms.cpa.mapper;

import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.CpaPartyEntity;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.mapstruct.*;

import java.util.List;

/**
 * MapStruct-mapper tussen CPA JPA-entiteiten en DTO's.
 * Spring-component via {@code componentModel = "spring"}.
 */
@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CpaMapper {

    // ── CpaEntity → CpaDto ────────────────────────────────────────────────

    @Mapping(target = "id",       expression = "java(entity.getId() != null ? entity.getId().toString() : null)")
    @Mapping(target = "parties",  source = "parties")
    @Mapping(target = "channels", ignore = true)
    CpaDto toDto(CpaEntity entity);

    List<CpaDto> toDtoList(List<CpaEntity> entities);

    // ── CpaDto → CpaEntity ────────────────────────────────────────────────

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "parties",   ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    CpaEntity toEntity(CpaDto dto);

    // ── CpaPartyEntity → PartyInfoDto ─────────────────────────────────────

    @Mapping(target = "id", expression = "java(e.getId() != null ? e.getId().toString() : null)")
    PartyInfoDto toPartyDto(CpaPartyEntity e);

    // ── CpaDeliveryChannelEntity → DeliveryChannelDto ─────────────────────

    @Mapping(target = "id", expression = "java(e.getId() != null ? e.getId().toString() : null)")
    DeliveryChannelDto toChannelDto(CpaDeliveryChannelEntity e);

    List<DeliveryChannelDto> toChannelDtoList(List<CpaDeliveryChannelEntity> entities);
}
