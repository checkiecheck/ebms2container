package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the new PUT/PATCH-status update methods in {@link CpaService}.
 * Mockito-only (no Spring / no Postgres) since Testcontainers/Docker are not
 * available in the sandbox.
 */
@ExtendWith(MockitoExtension.class)
class CpaServiceUpdateTest {

    @Mock  CpaRepository cpaRepository;
    @Mock  CpaPartyRepository partyRepository;
    @Mock  CpaDeliveryChannelRepository channelRepository;
    @Mock  PartnerCertificateRepository certRepository;
    @Mock  CpaMapper cpaMapper;
    @Mock  CpaPartyXmlParser partyXmlParser;

    @InjectMocks
    CpaService cpaService;

    private static final String CPA_ID = "urn:test:cpa:update-001";

    private CpaEntity existing;

    @BeforeEach
    void setUp() {
        lenient().when(partyXmlParser.parseParties(any())).thenReturn(new ArrayList<>());

        existing = CpaEntity.builder()
                .id(UUID.randomUUID())
                .cpaId(CPA_ID)
                .version("1.0")
                .description("orig")
                .status("ACTIVE")
                .cpaXml("<orig/>")
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2025-01-01T00:00:00Z"))
                .parties(new ArrayList<>())
                .build();
    }

    // ── update() ─────────────────────────────────────────────────────────

    @Test
    void update_existingCpa_overwritesMutableFields() {
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenAnswer(inv -> {
            CpaEntity e = inv.getArgument(0);
            return CpaDto.builder()
                    .cpaId(e.getCpaId()).version(e.getVersion())
                    .description(e.getDescription()).status(e.getStatus())
                    .cpaXml(e.getCpaXml()).createdAt(e.getCreatedAt()).build();
        });

        CpaDto in = CpaDto.builder()
                .cpaId(CPA_ID)
                .version("2.0")
                .description("new desc")
                .startDate(Instant.parse("2025-06-01T00:00:00Z"))
                .endDate(Instant.parse("2026-06-01T00:00:00Z"))
                .cpaXml("<new/>")
                .status("SUSPENDED")
                .build();

        CpaDto out = cpaService.update(CPA_ID, in);

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        verify(cpaRepository).save(cap.capture());
        CpaEntity saved = cap.getValue();

        assertThat(saved.getCpaId()).isEqualTo(CPA_ID);           // unchanged
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z")); // unchanged
        assertThat(saved.getVersion()).isEqualTo("2.0");
        assertThat(saved.getDescription()).isEqualTo("new desc");
        assertThat(saved.getStartDate()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        assertThat(saved.getEndDate()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(saved.getCpaXml()).isEqualTo("<new/>");
        assertThat(saved.getStatus()).isEqualTo("SUSPENDED");
        assertThat(out.getDescription()).isEqualTo("new desc");
    }

    @Test
    void update_nullOrBlankStatus_doesNotChangeStatus() {
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenReturn(CpaDto.builder().cpaId(CPA_ID).build());

        CpaDto in = CpaDto.builder().cpaId(CPA_ID).description("d").cpaXml("<x/>").status("   ").build();
        cpaService.update(CPA_ID, in);

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void update_nullVersion_preservesExistingVersion() {
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenReturn(CpaDto.builder().cpaId(CPA_ID).build());

        CpaDto in = CpaDto.builder().cpaId(CPA_ID).description("d").cpaXml("<x/>").build();
        cpaService.update(CPA_ID, in);

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getVersion()).isEqualTo("1.0");
    }

    @Test
    void update_missingCpa_throwsCpaNotFoundException() {
        when(cpaRepository.findByCpaId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cpaService.update("nope", CpaDto.builder().cpaId("nope").build()))
                .isInstanceOf(CpaNotFoundException.class);
        verify(cpaRepository, never()).save(any());
    }

    // ── updateStatus() ────────────────────────────────────────────────────

    @Test
    void updateStatus_active_persistsUppercase() {
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenReturn(CpaDto.builder().cpaId(CPA_ID).status("ACTIVE").build());

        cpaService.updateStatus(CPA_ID, "  active  ");

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateStatus_suspended_persistsUppercase() {
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenReturn(CpaDto.builder().cpaId(CPA_ID).status("SUSPENDED").build());

        cpaService.updateStatus(CPA_ID, "suspended");

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void updateStatus_invalidValue_throwsEbmsException() {
        assertThatThrownBy(() -> cpaService.updateStatus(CPA_ID, "FOO"))
                .isInstanceOf(EbmsException.class)
                .extracting("errorCode").isEqualTo("INVALID_STATUS");
        verify(cpaRepository, never()).findByCpaId(any());
    }

    @Test
    void updateStatus_null_throwsEbmsException() {
        assertThatThrownBy(() -> cpaService.updateStatus(CPA_ID, null))
                .isInstanceOf(EbmsException.class)
                .extracting("errorCode").isEqualTo("INVALID_STATUS");
    }

    @Test
    void updateStatus_blank_throwsEbmsException() {
        assertThatThrownBy(() -> cpaService.updateStatus(CPA_ID, "   "))
                .isInstanceOf(EbmsException.class)
                .extracting("errorCode").isEqualTo("INVALID_STATUS");
    }

    @Test
    void updateStatus_missingCpa_throwsCpaNotFoundException() {
        when(cpaRepository.findByCpaId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cpaService.updateStatus("nope", "ACTIVE"))
                .isInstanceOf(CpaNotFoundException.class);
    }
}
