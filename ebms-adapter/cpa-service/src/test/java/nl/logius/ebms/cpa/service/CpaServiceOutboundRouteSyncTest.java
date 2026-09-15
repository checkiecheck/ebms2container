package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaOutboundRouteRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CpaServiceOutboundRouteSyncTest {

    @Mock CpaRepository cpaRepository;
    @Mock CpaPartyRepository partyRepository;
    @Mock CpaDeliveryChannelRepository channelRepository;
    @Mock CpaOutboundRouteRepository routeRepository;
    @Mock PartnerCertificateRepository certRepository;
    @Mock CpaMapper cpaMapper;
    @Mock CpaPartyXmlParser partyXmlParser;

    @InjectMocks CpaService cpaService;

    @BeforeEach
    void setUp() {
        lenient().when(partyXmlParser.parseCpaId(anyString())).thenReturn("cpa-1");
        lenient().when(partyXmlParser.parseParties(anyString())).thenReturn(List.of());
        lenient().when(partyXmlParser.parseCertificates(anyString(), anyString())).thenReturn(List.of());
        lenient().when(partyXmlParser.parseDeliveryChannels(anyString(), anyString())).thenReturn(List.of());
        lenient().when(channelRepository.findByCpaId(anyString())).thenReturn(List.of());
        lenient().when(certRepository.findByCpaId(anyString())).thenReturn(List.of());
        lenient().when(routeRepository.findByCpaId(anyString())).thenReturn(List.of());
        lenient().when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(cpaMapper.toDto(any(CpaEntity.class))).thenReturn(CpaDto.builder().cpaId("cpa-1").build());
    }

    @Test
    void create_persistsAllRoutesParsedFromCpaXml() {
        CpaEntity cpa = CpaEntity.builder()
            .cpaId("cpa-1").cpaXml("<cpa/>").parties(new ArrayList<>()).build();
        CpaOutboundRouteEntity route = CpaOutboundRouteEntity.builder()
            .cpaId("cpa-1").fromPartyId("sender").toPartyId("receiver")
            .service("urn:test:service").action("Submit")
            .actionBindingId("send-submit")
            .fromRole("InitiatorRole").toRole("ResponderROLE")
            .channelPartyId("sender").channelId("channel-1").build();
        when(cpaRepository.existsByCpaId("cpa-1")).thenReturn(false);
        when(cpaMapper.toEntity(any(CpaDto.class))).thenReturn(cpa);
        when(partyXmlParser.parseOutboundRoutes("<cpa/>", "cpa-1")).thenReturn(List.of(route));

        cpaService.create(CpaDto.builder().cpaXml("<cpa/>").build());

        verify(routeRepository).saveAll(List.of(route));
    }
}