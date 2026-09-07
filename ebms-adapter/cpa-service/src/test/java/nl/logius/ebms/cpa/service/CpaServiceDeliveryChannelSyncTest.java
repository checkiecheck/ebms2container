package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
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
 * Verifieert {@code CpaService#syncDeliveryChannels(String, String)} reconciliation
 * (toevoegen / in-place bijwerken / verwijderen) binnen {@code create()} en
 * {@code update()}, inclusief de "XML wins" regel voor handmatig via
 * {@code addDeliveryChannel} toegevoegde kanalen (iteration_24).
 */
@ExtendWith(MockitoExtension.class)
class CpaServiceDeliveryChannelSyncTest {

    @Mock CpaRepository cpaRepository;
    @Mock CpaPartyRepository partyRepository;
    @Mock CpaDeliveryChannelRepository channelRepository;
    @Mock PartnerCertificateRepository certRepository;
    @Mock CpaMapper cpaMapper;
    @Mock CpaPartyXmlParser partyXmlParser;

    @InjectMocks CpaService cpaService;

    private static final String CPA_ID = "urn:test:cpa:ch-sync-001";
    private static final String PARTY_A = "00000000000000000001";
    private static final String PARTY_B = "00000000000000000002";

    @BeforeEach
    void setUp() {
        lenient().when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cpaMapper.toDto(any(CpaEntity.class))).thenAnswer(inv -> {
            CpaEntity e = inv.getArgument(0);
            return CpaDto.builder().cpaId(e.getCpaId()).cpaXml(e.getCpaXml()).build();
        });
        lenient().when(partyXmlParser.parseParties(any())).thenReturn(List.of());
        lenient().when(partyXmlParser.parseCpaId(any())).thenReturn(CPA_ID);
        lenient().when(partyXmlParser.parseStartDate(any())).thenReturn(null);
        lenient().when(partyXmlParser.parseEndDate(any())).thenReturn(null);
        lenient().when(partyXmlParser.parseCertificates(anyString(), any())).thenReturn(List.of());
    }

    private CpaDeliveryChannelEntity channel(String party, String channelId, String dk, String url) {
        return CpaDeliveryChannelEntity.builder()
            .id(UUID.randomUUID())
            .cpaId(CPA_ID)
            .partyId(party)
            .channelId(channelId)
            .dkProfile(dk)
            .transportProtocol("HTTP")
            .endpointUrl(url)
            .build();
    }

    private CpaEntity newCpaEntity() {
        return CpaEntity.builder().cpaId(CPA_ID).cpaXml("<xml/>").parties(new ArrayList<>()).build();
    }

    // ── create() ─────────────────────────────────────────────────────────

    @Test
    void create_insertsAllParsedChannelsWhenDbEmpty() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(newCpaEntity());
        when(channelRepository.findByCpaId(CPA_ID)).thenReturn(new ArrayList<>());
        List<CpaDeliveryChannelEntity> parsed = List.of(
            channel(PARTY_A, "ch1", "osb-rm-e", "https://a/"),
            channel(PARTY_B, "ch2", "osb-be", "https://b/"));
        when(partyXmlParser.parseDeliveryChannels(anyString(), any())).thenReturn(parsed);

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        ArgumentCaptor<List<CpaDeliveryChannelEntity>> saveCap = listCaptor();
        verify(channelRepository).saveAll(saveCap.capture());
        assertThat(saveCap.getValue()).hasSize(2);
        assertThat(saveCap.getValue()).extracting(CpaDeliveryChannelEntity::getChannelId)
            .containsExactlyInAnyOrder("ch1", "ch2");
        verify(channelRepository, never()).deleteAll(any());
    }

    @Test
    void create_noParsedNoExisting_doesNotSaveOrDelete() {
        when(cpaRepository.existsByCpaId(CPA_ID)).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(newCpaEntity());
        when(channelRepository.findByCpaId(CPA_ID)).thenReturn(new ArrayList<>());
        when(partyXmlParser.parseDeliveryChannels(anyString(), any())).thenReturn(List.of());

        cpaService.create(CpaDto.builder().cpaId(CPA_ID).cpaXml("<xml/>").build());

        verify(channelRepository, never()).saveAll(any());
        verify(channelRepository, never()).deleteAll(any());
    }

    // ── update() reconciliation ──────────────────────────────────────────

    @Test
    void update_reconcilesChannels_addsUpdatesInPlaceAndRemoves() {
        CpaDeliveryChannelEntity existingCh1 = channel(PARTY_A, "ch1", "osb-be", "http://old/");
        CpaDeliveryChannelEntity existingOld  = channel(PARTY_B, "old", "osb-be", "http://gone/");

        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(channelRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(existingCh1, existingOld)));

        CpaDeliveryChannelEntity parsedCh1  = channel(PARTY_A, "ch1", "osb-rm-e", "https://new/");
        parsedCh1.setRetryCount(3);
        parsedCh1.setRetryInterval(300);
        CpaDeliveryChannelEntity parsedNew = channel(PARTY_A, "ch2", "osb-be", "https://c/");
        when(partyXmlParser.parseDeliveryChannels(anyString(), any()))
            .thenReturn(List.of(parsedCh1, parsedNew));

        cpaService.update(CPA_ID, CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<List<CpaDeliveryChannelEntity>> delCap = listCaptor();
        verify(channelRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(1);
        assertThat(delCap.getValue().get(0).getChannelId()).isEqualTo("old");

        ArgumentCaptor<List<CpaDeliveryChannelEntity>> saveCap = listCaptor();
        verify(channelRepository).saveAll(saveCap.capture());
        List<CpaDeliveryChannelEntity> saved = saveCap.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(CpaDeliveryChannelEntity::getChannelId)
            .containsExactlyInAnyOrder("ch1", "ch2");

        // In-place update on ch1: same entity instance (PK preserved), fields refreshed
        CpaDeliveryChannelEntity ch1Saved = saved.stream()
            .filter(c -> "ch1".equals(c.getChannelId())).findFirst().orElseThrow();
        assertThat(ch1Saved).isSameAs(existingCh1);
        assertThat(ch1Saved.getDkProfile()).isEqualTo("osb-rm-e");
        assertThat(ch1Saved.getEndpointUrl()).isEqualTo("https://new/");
        assertThat(ch1Saved.getRetryCount()).isEqualTo(3);
        assertThat(ch1Saved.getRetryInterval()).isEqualTo(300);
    }

    /**
     * "XML wins over manual add" - a channel that was added via POST /api/cpa/{cpaId}/channels
     * (i.e. exists in DB, not in parsed set) MUST be deleted on the next create/update.
     */
    @Test
    void update_manuallyAddedChannelNotInXml_isDeletedByXmlWinsRule() {
        CpaDeliveryChannelEntity manual = channel(PARTY_A, "manual-ch", "osb-be", "http://manual/");
        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(channelRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(manual)));
        when(partyXmlParser.parseDeliveryChannels(anyString(), any()))
            .thenReturn(List.of(channel(PARTY_A, "xml-ch", "osb-rm", "https://xml/")));

        cpaService.update(CPA_ID, CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<List<CpaDeliveryChannelEntity>> delCap = listCaptor();
        verify(channelRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(1);
        assertThat(delCap.getValue().get(0).getChannelId()).isEqualTo("manual-ch");
    }

    @Test
    void update_allChannelsUnchanged_noDeleteButUpdatesInPlace() {
        CpaDeliveryChannelEntity existing = channel(PARTY_A, "ch1", "osb-be", "http://old/");
        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(channelRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(existing)));
        when(partyXmlParser.parseDeliveryChannels(anyString(), any()))
            .thenReturn(List.of(channel(PARTY_A, "ch1", "osb-rm-e", "https://new/")));

        cpaService.update(CPA_ID, CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        verify(channelRepository, never()).deleteAll(any());
        ArgumentCaptor<List<CpaDeliveryChannelEntity>> saveCap = listCaptor();
        verify(channelRepository).saveAll(saveCap.capture());
        assertThat(saveCap.getValue()).hasSize(1);
        assertThat(saveCap.getValue().get(0)).isSameAs(existing);
        assertThat(saveCap.getValue().get(0).getDkProfile()).isEqualTo("osb-rm-e");
        assertThat(saveCap.getValue().get(0).getEndpointUrl()).isEqualTo("https://new/");
    }

    @Test
    void update_withEmptyParsedList_removesAllExistingChannels() {
        CpaDeliveryChannelEntity e1 = channel(PARTY_A, "a", "osb-be", "u1");
        CpaDeliveryChannelEntity e2 = channel(PARTY_B, "b", "osb-be", "u2");
        CpaEntity entity = CpaEntity.builder()
            .id(UUID.randomUUID()).cpaId(CPA_ID).cpaXml("<old/>")
            .status("ACTIVE").parties(new ArrayList<>()).build();
        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(entity));
        when(channelRepository.findByCpaId(CPA_ID))
            .thenReturn(new ArrayList<>(List.of(e1, e2)));
        when(partyXmlParser.parseDeliveryChannels(anyString(), any())).thenReturn(List.of());

        cpaService.update(CPA_ID, CpaDto.builder().cpaId(CPA_ID).cpaXml("<new/>").description("d").build());

        ArgumentCaptor<List<CpaDeliveryChannelEntity>> delCap = listCaptor();
        verify(channelRepository).deleteAll(delCap.capture());
        assertThat(delCap.getValue()).hasSize(2);
        verify(channelRepository, never()).saveAll(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<CpaDeliveryChannelEntity>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
