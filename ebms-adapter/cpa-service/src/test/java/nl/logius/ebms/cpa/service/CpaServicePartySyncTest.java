package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.CpaPartyEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code syncParties()} behavior inside {@link CpaService#create} and
 * {@link CpaService#update} using a mocked {@link CpaPartyXmlParser}.
 */
@ExtendWith(MockitoExtension.class)
class CpaServicePartySyncTest {

    @Mock CpaRepository cpaRepository;
    @Mock CpaPartyRepository partyRepository;
    @Mock CpaDeliveryChannelRepository channelRepository;
    @Mock PartnerCertificateRepository certRepository;
    @Mock CpaMapper cpaMapper;
    @Mock CpaPartyXmlParser partyXmlParser;

    @InjectMocks
    CpaService cpaService;

    private static final String CPA_ID = "urn:test:cpa:party-sync-001";
    private static final String OIN_A = "00000000000000000010";
    private static final String OIN_B = "00000000000000000020";
    private static final String OIN_C = "00000000000000000030";

    @BeforeEach
    void setUp() {
        lenient().when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cpaMapper.toDto(any(CpaEntity.class))).thenAnswer(inv -> {
            CpaEntity e = inv.getArgument(0);
            return CpaDto.builder().cpaId(e.getCpaId()).cpaXml(e.getCpaXml()).build();
        });
        lenient().when(partyXmlParser.parseCpaId(any())).thenReturn(CPA_ID);
        lenient().when(partyXmlParser.parseStartDate(any())).thenReturn(null);
        lenient().when(partyXmlParser.parseEndDate(any())).thenReturn(null);
    }

    private PartyInfoDto party(String partyId, String role, String service) {
        return PartyInfoDto.builder()
            .partyId(partyId)
            .partyIdType("urn:oin")
            .oin(partyId)
            .oinValidated(true)
            .role(role)
            .service(service)
            .build();
    }

    private CpaPartyEntity partyEntity(String partyId, String role) {
        return CpaPartyEntity.builder()
            .id(UUID.randomUUID())
            .cpaId(CPA_ID)
            .partyId(partyId)
            .partyIdType("urn:oin")
            .oin(partyId)
            .oinValidated(true)
            .role(role)
            .service("svc-old")
            .build();
    }

    // ── create() ─────────────────────────────────────────────────────────

    @Test
    void create_populatesPartiesFromParsedXml() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(partyXmlParser.parseParties(any()))
            .thenReturn(List.of(party(OIN_A, "Sender", "svc1"),
                                party(OIN_B, "Receiver", "svc2")));
        CpaEntity mapped = CpaEntity.builder()
            .cpaId(CPA_ID).cpaXml("<xml/>").parties(new ArrayList<>()).build();
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(mapped);

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        org.mockito.Mockito.verify(cpaRepository).save(cap.capture());
        List<CpaPartyEntity> saved = cap.getValue().getParties();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(CpaPartyEntity::getPartyId).containsExactlyInAnyOrder(OIN_A, OIN_B);
        assertThat(saved).allMatch(p -> CPA_ID.equals(p.getCpaId()));
        assertThat(saved).allMatch(CpaPartyEntity::isOinValidated);
        assertThat(saved).extracting(CpaPartyEntity::getRole).contains("Sender", "Receiver");
    }

    @Test
    void create_withEmptyParsedList_leavesPartiesEmpty() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(partyXmlParser.parseParties(any())).thenReturn(new ArrayList<>());
        CpaEntity mapped = CpaEntity.builder()
            .cpaId(CPA_ID).cpaXml("<xml/>").parties(new ArrayList<>()).build();
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(mapped);

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        org.mockito.Mockito.verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getParties()).isEmpty();
    }

    // ── update() reconciliation ──────────────────────────────────────────

    @Test
    void update_reconcilesParties_removesUpdatesAndAdds() {
        // Existing: A, B
        CpaPartyEntity existingA = partyEntity(OIN_A, "OldRoleA");
        CpaPartyEntity existingB = partyEntity(OIN_B, "OldRoleB");
        ArrayList<CpaPartyEntity> existingList = new ArrayList<>(List.of(existingA, existingB));

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID())
            .cpaId(CPA_ID)
            .cpaXml("<old/>")
            .status("ACTIVE")
            .parties(existingList)
            .build();

        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        // New XML has: A (updated role), C (new). B removed.
        when(partyXmlParser.parseParties(any()))
            .thenReturn(List.of(party(OIN_A, "NewRoleA", "svc-new-a"),
                                party(OIN_C, "NewRoleC", "svc-new-c")));

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        org.mockito.Mockito.verify(cpaRepository).save(cap.capture());
        List<CpaPartyEntity> finalParties = cap.getValue().getParties();

        assertThat(finalParties).hasSize(2);
        assertThat(finalParties).extracting(CpaPartyEntity::getPartyId)
            .containsExactlyInAnyOrder(OIN_A, OIN_C);
        // B is gone
        assertThat(finalParties).noneMatch(p -> OIN_B.equals(p.getPartyId()));

        // A updated in place - same identity (same UUID as existingA)
        CpaPartyEntity aFinal = finalParties.stream()
            .filter(p -> OIN_A.equals(p.getPartyId())).findFirst().orElseThrow();
        assertThat(aFinal).isSameAs(existingA);
        assertThat(aFinal.getRole()).isEqualTo("NewRoleA");
        assertThat(aFinal.getService()).isEqualTo("svc-new-a");

        // C is newly added
        CpaPartyEntity cFinal = finalParties.stream()
            .filter(p -> OIN_C.equals(p.getPartyId())).findFirst().orElseThrow();
        assertThat(cFinal.getRole()).isEqualTo("NewRoleC");
        assertThat(cFinal.getCpaId()).isEqualTo(CPA_ID);
        assertThat(cFinal.isOinValidated()).isTrue();
    }

    @Test
    void update_withEmptyParsedList_removesAllExistingParties() {
        ArrayList<CpaPartyEntity> existingList = new ArrayList<>(List.of(
            partyEntity(OIN_A, "R1"), partyEntity(OIN_B, "R2")));

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(existingList).build();

        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(partyXmlParser.parseParties(any())).thenReturn(new ArrayList<>());

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        org.mockito.Mockito.verify(cpaRepository).save(cap.capture());
        assertThat(cap.getValue().getParties()).isEmpty();
    }

    @Test
    void update_allPartiesUnchanged_noDuplicatesInPlaceUpdate() {
        CpaPartyEntity existingA = partyEntity(OIN_A, "Role");
        ArrayList<CpaPartyEntity> existingList = new ArrayList<>(List.of(existingA));

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(existingList).build();

        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(partyXmlParser.parseParties(any()))
            .thenReturn(List.of(party(OIN_A, "Role", "svc-old")));

        cpaService.update(CPA_ID,
            CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<CpaEntity> cap = ArgumentCaptor.forClass(CpaEntity.class);
        org.mockito.Mockito.verify(cpaRepository).save(cap.capture());
        List<CpaPartyEntity> finalParties = cap.getValue().getParties();
        assertThat(finalParties).hasSize(1);
        assertThat(finalParties.get(0)).isSameAs(existingA);
    }
}
