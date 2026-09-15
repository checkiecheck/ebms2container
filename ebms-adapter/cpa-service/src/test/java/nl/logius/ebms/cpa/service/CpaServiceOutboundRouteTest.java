package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaOutboundRouteRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CpaServiceOutboundRouteTest {

    @Mock CpaRepository cpaRepository;
    @Mock CpaPartyRepository partyRepository;
    @Mock CpaDeliveryChannelRepository channelRepository;
    @Mock CpaOutboundRouteRepository routeRepository;
    @Mock PartnerCertificateRepository certRepository;
    @Mock CpaMapper cpaMapper;
    @Mock CpaPartyXmlParser partyXmlParser;

    @InjectMocks CpaService cpaService;

    @Test
    void findOutboundRoute_returnsExactCaseRolesAndReferencedChannel() {
        CpaOutboundRouteEntity route = route("InitiatorRole", "ResponderROLE", "channel-1");
        CpaDeliveryChannelEntity channel = CpaDeliveryChannelEntity.builder()
            .cpaId("cpa-1").partyId("sender").channelId("channel-1")
            .dkProfile("osb-rm").endpointUrl("https://partner.example/ebms").build();
        DeliveryChannelDto channelDto = DeliveryChannelDto.builder()
            .cpaId("cpa-1").partyId("sender").channelId("channel-1")
            .dkProfile("osb-rm").endpointUrl("https://partner.example/ebms").build();
        when(routeRepository.findByCpaIdAndFromPartyIdAndToPartyId(
            "cpa-1", "sender", "receiver"))
            .thenReturn(List.of(route));
        when(channelRepository.findByCpaIdAndPartyIdAndChannelId(
            "cpa-1", "sender", "channel-1")).thenReturn(Optional.of(channel));
        when(cpaMapper.toChannelDto(channel)).thenReturn(channelDto);

        OutboundRouteDto result = cpaService.findOutboundRoute(
            "cpa-1", "sender", "receiver", "urn:test:service", null, "Submit", null, null);

        assertThat(result.getFromRole()).isEqualTo("InitiatorRole");
        assertThat(result.getToRole()).isEqualTo("ResponderROLE");
        assertThat(result.getChannel()).isSameAs(channelDto);
    }

    @Test
    void findOutboundRoute_rejectsMultipleChannelsForSameMessageRoute() {
        when(routeRepository.findByCpaIdAndFromPartyIdAndToPartyId(
            "cpa-1", "sender", "receiver"))
            .thenReturn(List.of(
                route("InitiatorRole", "ResponderROLE", "channel-1"),
                route("InitiatorRole", "ResponderROLE", "channel-2")));

        assertThatThrownBy(() -> cpaService.findOutboundRoute(
            "cpa-1", "sender", "receiver", "urn:test:service", null, "Submit", null, null))
            .isInstanceOf(EbmsException.class)
            .satisfies(failure -> assertThat(((EbmsException) failure).getErrorCode())
                .isEqualTo("ROUTE_AMBIGUOUS"));
    }

    @Test
    void findOutboundRoute_usesSuppliedRolesToDisambiguateBindingsCaseSensitively() {
        CpaOutboundRouteEntity selected = route("InitiatorRole", "ResponderROLE", "channel-1");
        CpaOutboundRouteEntity other = route("OtherInitiator", "OtherResponder", "channel-2");
        CpaDeliveryChannelEntity channel = CpaDeliveryChannelEntity.builder()
            .cpaId("cpa-1").partyId("sender").channelId("channel-1")
            .dkProfile("osb-be").endpointUrl("https://partner.example/ebms").build();
        when(routeRepository.findByCpaIdAndFromPartyIdAndToPartyId(
            "cpa-1", "sender", "receiver")).thenReturn(List.of(selected, other));
        when(channelRepository.findByCpaIdAndPartyIdAndChannelId(
            "cpa-1", "sender", "channel-1")).thenReturn(Optional.of(channel));
        when(cpaMapper.toChannelDto(channel)).thenReturn(DeliveryChannelDto.builder()
            .endpointUrl("https://partner.example/ebms").dkProfile("osb-be").build());

        OutboundRouteDto result = cpaService.findOutboundRoute(
            "cpa-1", "sender", "receiver", "urn:test:service", null, "Submit",
            "InitiatorRole", "ResponderROLE");

        assertThat(result.getFromRole()).isEqualTo("InitiatorRole");
        assertThat(result.getToRole()).isEqualTo("ResponderROLE");
        assertThat(result.getChannel().getEndpointUrl()).isEqualTo("https://partner.example/ebms");
    }

    @Test
    void findOutboundRoute_usesCaseSensitiveLookupValues() {
        when(routeRepository.findByCpaIdAndFromPartyIdAndToPartyId(
            "cpa-1", "sender", "receiver")).thenReturn(List.of());
        when(routeRepository.findByCpaId("cpa-1")).thenReturn(List.of(route(
            "InitiatorRole", "ResponderROLE", "channel-1")));

        assertThatThrownBy(() -> cpaService.findOutboundRoute(
            "cpa-1", "sender", "receiver", "urn:test:service", null, "submit", null, null))
            .isInstanceOf(EbmsException.class)
            .satisfies(failure -> assertThat(((EbmsException) failure).getErrorCode())
                .isEqualTo("ROUTE_NOT_FOUND"));

        verify(routeRepository).findByCpaIdAndFromPartyIdAndToPartyId(
            "cpa-1", "sender", "receiver");
    }

    private CpaOutboundRouteEntity route(String fromRole, String toRole, String channelId) {
        return CpaOutboundRouteEntity.builder()
            .cpaId("cpa-1")
            .fromPartyId("sender")
            .toPartyId("receiver")
            .service("urn:test:service")
            .action("Submit")
            .actionBindingId("send-" + channelId)
            .fromRole(fromRole)
            .toRole(toRole)
            .channelPartyId("sender")
            .channelId(channelId)
            .build();
    }
}