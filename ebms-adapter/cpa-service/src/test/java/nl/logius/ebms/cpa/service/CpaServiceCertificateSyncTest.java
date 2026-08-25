package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link CpaService#syncCertificates(String, String)} reconciliation
 * (add / update-in-place / remove) inside {@code create()} and {@code update()},
 * including the "XML wins over manual upload" rule.
 */
@ExtendWith(MockitoExtension.class)
class CpaServiceCertificateSyncTest {

    @Mock CpaRepository cpaRepository;
    @Mock CpaPartyRepository partyRepository;
    @Mock CpaDeliveryChannelRepository channelRepository;
    @Mock PartnerCertificateRepository certRepository;
    @Mock CpaMapper cpaMapper;
    @Mock CpaPartyXmlParser partyXmlParser;

    @InjectMocks
    CpaService cpaService;

    private static final String CPA_ID = "urn:test:cpa:cert-sync-001";
    private static final String PARTY_A = "00000000000000000001";
    private static final String PARTY_B = "00000000000000000002";

    @BeforeEach
    void setUp() {
        lenient().when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cpaMapper.toDto(any(CpaEntity.class))).thenAnswer(inv -> {
            CpaEntity e = inv.getArgument(0);
            return CpaDto.builder().cpaId(e.getCpaId()).cpaXml(e.getCpaXml()).build();
        });
        // parseParties is unconditionally invoked; return empty by default
        lenient().when(partyXmlParser.parseParties(any())).thenReturn(List.of());
    }

    private PartnerCertificateEntity cert(String party, String alias, String pem, String usage) {
        return PartnerCertificateEntity.builder()
            .id(UUID.randomUUID())
            .cpaId(CPA_ID)
            .partyId(party)
            .certificateAlias(alias)
            .certificatePem(pem)
            .validFrom(Instant.parse("2024-01-01T00:00:00Z"))
            .validUntil(Instant.parse("2026-01-01T00:00:00Z"))
            .certificateUsage(usage)
            .build();
    }

    private CpaEntity newCpaEntity() {
        return CpaEntity.builder()
            .cpaId(CPA_ID).cpaXml("<xml/>").parties(new ArrayList<>()).build();
    }

    // ── create() ─────────────────────────────────────────────────────────

    @Test
    void create_insertsAllParsedCertsWhenDbEmpty() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(newCpaEntity());
        when(certRepository.findByCpaId(CPA_ID)).thenReturn(new ArrayList<>());
        List<PartnerCertificateEntity> parsed = List.of(
            cert(PARTY_A, "signing-a", "PEM-A-sign", "SIGNING"),
            cert(PARTY_A, "encryption-a", "PEM-A-enc", "ENCRYPTION"));
        when(partyXmlParser.parseCertificates(anyString(), any())).thenReturn(parsed);

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        ArgumentCaptor<List<PartnerCertificateEntity>> saveCap = listCaptor();
        verify(certRepository).saveAll(saveCap.capture());
        assertThat(saveCap.getValue()).hasSize(2);
        assertThat(saveCap.getValue()).extracting(PartnerCertificateEntity::getCertificateAlias)
            .containsExactlyInAnyOrder("signing-a", "encryption-a");
        verify(certRepository, never()).deleteAll(any());
    }

    @Test
    void create_withNoParsedCertsAndNoExisting_doesNotSaveOrDelete() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(newCpaEntity());
        when(certRepository.findByCpaId(CPA_ID)).thenReturn(new ArrayList<>());
        when(partyXmlParser.parseCertificates(anyString(), any())).thenReturn(List.of());

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        verify(certRepository, never()).saveAll(any());
        verify(certRepository, never()).deleteAll(any());
    }

    // ── update() reconciliation ──────────────────────────────────────────

    @Test
    void update_reconcilesCerts_addsUpdatesInPlaceAndRemoves() {
        // Existing DB rows: sign-a (will be updated), old-b (will be removed)
        PartnerCertificateEntity existingSignA =
            cert(PARTY_A, "sign-a", "OLD-PEM-A", "SIGNING");
        PartnerCertificateEntity existingOldB =
            cert(PARTY_B, "old-b", "OLD-PEM-B", "SIGNING");

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(certRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(existingSignA, existingOldB)));

        // New XML: sign-a (updated PEM/usage) + new-c (new insert). old-b removed.
        PartnerCertificateEntity parsedSignA =
            cert(PARTY_A, "sign-a", "NEW-PEM-A", "SIGNING");
        PartnerCertificateEntity parsedNewC =
            cert(PARTY_A, "new-c", "PEM-C", "ENCRYPTION");
        when(partyXmlParser.parseCertificates(anyString(), any()))
            .thenReturn(List.of(parsedSignA, parsedNewC));

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        // Verify deleteAll removed the not-in-XML row
        ArgumentCaptor<List<PartnerCertificateEntity>> delCap = listCaptor();
        verify(certRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(1);
        assertThat(delCap.getValue().get(0).getCertificateAlias()).isEqualTo("old-b");

        // Verify saveAll contains update-in-place for sign-a AND new insert new-c
        ArgumentCaptor<List<PartnerCertificateEntity>> saveCap = listCaptor();
        verify(certRepository).saveAll(saveCap.capture());
        List<PartnerCertificateEntity> saved = saveCap.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(PartnerCertificateEntity::getCertificateAlias)
            .containsExactlyInAnyOrder("sign-a", "new-c");

        // The sign-a row must be the SAME entity as existingSignA (in-place update, PK preserved)
        PartnerCertificateEntity signAUpdated = saved.stream()
            .filter(c -> "sign-a".equals(c.getCertificateAlias())).findFirst().orElseThrow();
        assertThat(signAUpdated).isSameAs(existingSignA);
        assertThat(signAUpdated.getCertificatePem()).isEqualTo("NEW-PEM-A");

        // The new-c row is the parsed transient entity itself
        PartnerCertificateEntity newC = saved.stream()
            .filter(c -> "new-c".equals(c.getCertificateAlias())).findFirst().orElseThrow();
        assertThat(newC.getCertificatePem()).isEqualTo("PEM-C");
    }

    /**
     * The user-explicit "XML wins" rule: a certificate that was previously added manually via
     * {@link CpaService#addCertificate} (i.e. it exists in the DB but has no matching entry in
     * the newly parsed set) MUST be included in the deleteAll(...) call on the next
     * create/update of this CPA.
     */
    @Test
    void update_manuallyAddedCertNotInXml_isDeletedByXmlWinsRule() {
        PartnerCertificateEntity manualUpload =
            cert(PARTY_A, "manually-uploaded", "MANUAL-PEM", "SIGNING");

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(certRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(manualUpload)));
        // XML declares a different cert only
        when(partyXmlParser.parseCertificates(anyString(), any()))
            .thenReturn(List.of(cert(PARTY_A, "xml-signing", "XML-PEM", "SIGNING")));

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<List<PartnerCertificateEntity>> delCap = listCaptor();
        verify(certRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(1);
        assertThat(delCap.getValue().get(0).getCertificateAlias()).isEqualTo("manually-uploaded");
    }

    /**
     * XML-wins on create as well: if create() runs against a DB row that already exists
     * for this cpaId (unlikely for real create, but the reconciliation logic is symmetric),
     * the not-in-XML row is deleted.
     */
    @Test
    void create_manuallyAddedCertNotInXml_isDeletedByXmlWinsRule() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(newCpaEntity());
        PartnerCertificateEntity manual = cert(PARTY_A, "manual", "PEM", "SIGNING");
        when(certRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(manual)));
        when(partyXmlParser.parseCertificates(anyString(), any())).thenReturn(List.of());

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        ArgumentCaptor<List<PartnerCertificateEntity>> delCap = listCaptor();
        verify(certRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).extracting(PartnerCertificateEntity::getCertificateAlias)
            .containsExactly("manual");
        verify(certRepository, never()).saveAll(any());
    }

    @Test
    void update_allCertsUnchanged_noDeleteButUpdatesInPlace() {
        PartnerCertificateEntity existing = cert(PARTY_A, "sign-a", "OLD", "SIGNING");
        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(certRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(existing)));
        when(partyXmlParser.parseCertificates(anyString(), any()))
            .thenReturn(List.of(cert(PARTY_A, "sign-a", "NEW", "SIGNING")));

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        verify(certRepository, never()).deleteAll(any());
        ArgumentCaptor<List<PartnerCertificateEntity>> saveCap = listCaptor();
        verify(certRepository).saveAll(saveCap.capture());
        assertThat(saveCap.getValue()).hasSize(1);
        assertThat(saveCap.getValue().get(0)).isSameAs(existing);
        assertThat(saveCap.getValue().get(0).getCertificatePem()).isEqualTo("NEW");
    }

    @Test
    void update_withEmptyParsedList_removesAllExistingCerts() {
        PartnerCertificateEntity e1 = cert(PARTY_A, "a", "PA", "SIGNING");
        PartnerCertificateEntity e2 = cert(PARTY_B, "b", "PB", "ENCRYPTION");
        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(certRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(e1, e2)));
        when(partyXmlParser.parseCertificates(anyString(), any())).thenReturn(List.of());

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<List<PartnerCertificateEntity>> delCap = listCaptor();
        verify(certRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(2);
        verify(certRepository, never()).saveAll(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<PartnerCertificateEntity>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
