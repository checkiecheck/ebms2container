package nl.logius.ebms.cpa.util;

import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests voor de nieuwe iteration_30 feature:
 * extractie van {@code MessagingCharacteristics/@syncReplyMode} uit de {@code <DocExchange>}
 * per DeliveryChannel (Reliable Messaging - Async ACKs).
 *
 * <p>Digikoppeling Koppelvlakstandaard ebMS2 v3.3+:
 * {@code syncReplyMode="none"} = async (default), {@code "mshSignalsOnly"} = sync (opt-in).
 */
class CpaPartyXmlParserSyncReplyModeTest {

    private final CpaPartyXmlParser parser = new CpaPartyXmlParser();
    private static final String CPA_ID = "urn:test:cpa:srm";
    private static final String PARTY = "00000000000000000001";

    @Test
    void parseDeliveryChannels_syncReplyModeNone_extractedAsync() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='d'>"
            + "    <MessagingCharacteristics syncReplyMode='none'/>"
            + "  </DocExchange>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isEqualTo("none");
    }

    @Test
    void parseDeliveryChannels_syncReplyModeMshSignalsOnly_extractedSync() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='d'>"
            + "    <MessagingCharacteristics syncReplyMode='mshSignalsOnly'/>"
            + "  </DocExchange>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isEqualTo("mshSignalsOnly");
    }

    @Test
    void parseDeliveryChannels_noMessagingCharacteristics_syncReplyModeNull() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='d'/>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isNull();
    }

    @Test
    void parseDeliveryChannels_noDocExchange_syncReplyModeNull() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isNull();
    }

    @Test
    void parseDeliveryChannels_namespacePrefixedSyncReplyMode_extracted() {
        String xml = ""
            + "<tp:Root xmlns:tp='http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd'>"
            + "  <tp:PartyInfo>"
            + "    <tp:PartyId>" + PARTY + "</tp:PartyId>"
            + "    <tp:DeliveryChannel tp:channelId='c' tp:transportId='t' tp:docExchangeId='d'/>"
            + "    <tp:Transport tp:transportId='t'>"
            + "      <tp:TransportSender><tp:Endpoint tp:uri='https://e/'/></tp:TransportSender>"
            + "    </tp:Transport>"
            + "    <tp:DocExchange tp:docExchangeId='d'>"
            + "      <tp:MessagingCharacteristics tp:syncReplyMode='none'/>"
            + "    </tp:DocExchange>"
            + "  </tp:PartyInfo>"
            + "</tp:Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isEqualTo("none");
    }

    @Test
    void parseDeliveryChannels_directMessagingCharacteristicsOnChannel_extracted() {
        String xml = ""
            + "<tns:Root xmlns:tns='http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd'>"
            + "  <tns:PartyInfo>"
            + "    <tns:PartyId>" + PARTY + "</tns:PartyId>"
            + "    <tns:DeliveryChannel tns:channelId='c' tns:transportId='t'"
            + "        tns:docExchangeId='missing'>"
            + "      <tns:MessagingCharacteristics tns:syncReplyMode='none'/>"
            + "    </tns:DeliveryChannel>"
            + "    <tns:Transport tns:transportId='t'>"
            + "      <tns:TransportSender><tns:Endpoint tns:uri='https://e/'/></tns:TransportSender>"
            + "    </tns:Transport>"
            + "  </tns:PartyInfo>"
            + "</tns:Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);

        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isEqualTo("none");
    }

    @Test
    void parseDeliveryChannels_emptySyncReplyMode_treatedAsNull() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='d'>"
            + "    <MessagingCharacteristics syncReplyMode=''/>"
            + "  </DocExchange>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getSyncReplyMode()).isNull();
    }
}
