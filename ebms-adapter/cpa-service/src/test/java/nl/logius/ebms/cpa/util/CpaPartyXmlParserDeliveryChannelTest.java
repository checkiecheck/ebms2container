package nl.logius.ebms.cpa.util;

import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests voor {@link CpaPartyXmlParser#parseDeliveryChannels(String, String)}.
 * Dekt de nieuwe iteration_24 feature: extractie van DeliveryChannel/Transport/DocExchange
 * per PartyInfo (ID/IDREF resolutie), dkProfile heuristiek, endpointUrl, retry/persist,
 * en namespace-prefixed varianten.
 */
class CpaPartyXmlParserDeliveryChannelTest {

    private final CpaPartyXmlParser parser = new CpaPartyXmlParser();
    private static final String CPA_ID = "urn:test:cpa:ch-001";
    private static final String PARTY_A = "00000000000000000001";
    private static final String PARTY_B = "00000000000000000002";

    // ── Test case A: full RM + sign + encrypt -> osb-rm-e ────────────────

    @Test
    void parseDeliveryChannels_caseA_reliableMessagingSignedEncrypted_iso8601Duration() {
        String xml = ""
            + "<CollaborationProtocolAgreement>"
            + "  <PartyInfo>"
            + "    <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "    <DeliveryChannel channelId='ch1' transportId='t1' docExchangeId='dx1'/>"
            + "    <Transport transportId='t1'>"
            + "      <TransportSender>"
            + "        <Endpoint uri='https://party-a.example.com/ebms'/>"
            + "      </TransportSender>"
            + "    </Transport>"
            + "    <DocExchange docExchangeId='dx1'>"
            + "      <ebXMLSenderBinding>"
            + "        <ReliableMessaging>"
            + "          <Retries>3</Retries>"
            + "          <RetryInterval>PT5M</RetryInterval>"
            + "          <PersistDuration>P1D</PersistDuration>"
            + "        </ReliableMessaging>"
            + "        <SenderNonRepudiation/>"
            + "        <SenderDigitalEnvelope/>"
            + "      </ebXMLSenderBinding>"
            + "    </DocExchange>"
            + "  </PartyInfo>"
            + "</CollaborationProtocolAgreement>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);

        assertThat(channels).hasSize(1);
        CpaDeliveryChannelEntity c = channels.get(0);
        assertThat(c.getCpaId()).isEqualTo(CPA_ID);
        assertThat(c.getPartyId()).isEqualTo(PARTY_A);
        assertThat(c.getChannelId()).isEqualTo("ch1");
        assertThat(c.getDkProfile()).isEqualTo("osb-rm-e");
        assertThat(c.getEndpointUrl()).isEqualTo("https://party-a.example.com/ebms");
        assertThat(c.getRetryCount()).isEqualTo(3);
        assertThat(c.getRetryInterval()).isEqualTo(300);       // PT5M = 300s
        assertThat(c.getPersistDuration()).isEqualTo(86400);   // P1D = 86400s
        assertThat(c.getTransportProtocol()).isEqualTo("HTTP");
    }

    @Test
    void parseDeliveryChannels_caseA_plainSecondsRetryInterval() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY_A + "</PartyId>"
            + "  <DeliveryChannel channelId='ch1' transportId='t1' docExchangeId='dx1'/>"
            + "  <Transport transportId='t1'><TransportSender><Endpoint uri='https://x/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='dx1'><ebXMLSenderBinding>"
            + "    <ReliableMessaging><Retries>2</Retries><RetryInterval>300</RetryInterval></ReliableMessaging>"
            + "    <SenderNonRepudiation/><SenderDigitalEnvelope/>"
            + "  </ebXMLSenderBinding></DocExchange>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getRetryInterval()).isEqualTo(300);
        assertThat(channels.get(0).getRetryCount()).isEqualTo(2);
        assertThat(channels.get(0).getDkProfile()).isEqualTo("osb-rm-e");
    }

    // ── Test case B: no DocExchange link / no RM/sign/enc -> osb-be ──────

    @Test
    void parseDeliveryChannels_caseB_noDocExchange_fallbackOsbBe() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY_A + "</PartyId>"
            + "  <DeliveryChannel channelId='chB' transportId='tB'/>"
            + "  <Transport transportId='tB'><TransportReceiver><Endpoint uri='http://plain/'/></TransportReceiver></Transport>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        CpaDeliveryChannelEntity c = channels.get(0);
        assertThat(c.getDkProfile()).isEqualTo("osb-be");
        assertThat(c.getEndpointUrl()).isEqualTo("http://plain/");
        assertThat(c.getRetryCount()).isNull();
        assertThat(c.getRetryInterval()).isNull();
        assertThat(c.getPersistDuration()).isNull();
    }

    @Test
    void parseDeliveryChannels_docExchangeWithoutRmOrSignOrEnc_returnsOsbBe() {
        String xml = ""
            + "<Root><PartyInfo>"
            + "  <PartyId>" + PARTY_A + "</PartyId>"
            + "  <DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "  <Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "  <DocExchange docExchangeId='d'><ebXMLSenderBinding/></DocExchange>"
            + "</PartyInfo></Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getDkProfile()).isEqualTo("osb-be");
    }

    // ── DK-profile heuristiek matrix ─────────────────────────────────────

    @Test
    void parseDeliveryChannels_signedOnly_isOsbBeS() {
        String xml = dxOnly("<SenderNonRepudiation/>");
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID).get(0).getDkProfile()).isEqualTo("osb-be-s");
    }

    @Test
    void parseDeliveryChannels_rmOnly_isOsbRm() {
        String xml = dxOnly("<ReliableMessaging><Retries>1</Retries><RetryInterval>60</RetryInterval></ReliableMessaging>");
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID).get(0).getDkProfile()).isEqualTo("osb-rm");
    }

    @Test
    void parseDeliveryChannels_rmSigned_isOsbRmS() {
        String xml = dxOnly(
            "<ReliableMessaging><Retries>1</Retries><RetryInterval>60</RetryInterval></ReliableMessaging>"
            + "<ReceiverNonRepudiation/>");
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID).get(0).getDkProfile()).isEqualTo("osb-rm-s");
    }

    @Test
    void parseDeliveryChannels_encryptedOnly_isOsbBeE() {
        String xml = dxOnly("<ReceiverDigitalEnvelope/>");
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID).get(0).getDkProfile()).isEqualTo("osb-be-e");
    }

    /** Builds a minimal single-party XML with the given contents inside DocExchange. */
    private String dxOnly(String docExchangeInner) {
        return "<Root><PartyInfo>"
            + "<PartyId>" + PARTY_A + "</PartyId>"
            + "<DeliveryChannel channelId='c' transportId='t' docExchangeId='d'/>"
            + "<Transport transportId='t'><TransportSender><Endpoint uri='https://e/'/></TransportSender></Transport>"
            + "<DocExchange docExchangeId='d'>" + docExchangeInner + "</DocExchange>"
            + "</PartyInfo></Root>";
    }

    // ── Test case C: 2 parties, per-party ID isolation ───────────────────

    @Test
    void parseDeliveryChannels_caseC_twoParties_perPartyIdIsolation() {
        // Both parties reuse channelId='ch' / transportId='t' / docExchangeId='d' -
        // resolution must be scoped per PartyInfo.
        String xml = ""
            + "<Root>"
            + "  <PartyInfo>"
            + "    <PartyId>" + PARTY_A + "</PartyId>"
            + "    <DeliveryChannel channelId='ch' transportId='t' docExchangeId='d'/>"
            + "    <Transport transportId='t'><TransportSender><Endpoint uri='https://A/'/></TransportSender></Transport>"
            + "    <DocExchange docExchangeId='d'><ebXMLSenderBinding><SenderNonRepudiation/></ebXMLSenderBinding></DocExchange>"
            + "  </PartyInfo>"
            + "  <PartyInfo>"
            + "    <PartyId>" + PARTY_B + "</PartyId>"
            + "    <DeliveryChannel channelId='ch' transportId='t' docExchangeId='d'/>"
            + "    <Transport transportId='t'><TransportReceiver><Endpoint uri='https://B/'/></TransportReceiver></Transport>"
            + "    <DocExchange docExchangeId='d'><ebXMLReceiverBinding><ReceiverDigitalEnvelope/></ebXMLReceiverBinding></DocExchange>"
            + "  </PartyInfo>"
            + "</Root>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(2);
        CpaDeliveryChannelEntity a = channels.stream().filter(c -> PARTY_A.equals(c.getPartyId())).findFirst().orElseThrow();
        CpaDeliveryChannelEntity b = channels.stream().filter(c -> PARTY_B.equals(c.getPartyId())).findFirst().orElseThrow();
        assertThat(a.getEndpointUrl()).isEqualTo("https://A/");
        assertThat(a.getDkProfile()).isEqualTo("osb-be-s");
        assertThat(b.getEndpointUrl()).isEqualTo("https://B/");
        assertThat(b.getDkProfile()).isEqualTo("osb-be-e");
    }

    // ── Test case D: namespace-prefixed variant (elements + attributes) ──

    @Test
    void parseDeliveryChannels_caseD_namespacePrefixedElementsAndAttributes() {
        String xml = ""
            + "<tp:CollaborationProtocolAgreement xmlns:tp='http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd'>"
            + "  <tp:PartyInfo>"
            + "    <tp:PartyId tp:type='urn:oin'>" + PARTY_A + "</tp:PartyId>"
            + "    <tp:DeliveryChannel tp:channelId='ch1' tp:transportId='t1' tp:docExchangeId='dx1'/>"
            + "    <tp:Transport tp:transportId='t1'>"
            + "      <tp:TransportSender><tp:Endpoint tp:uri='https://party-a.example.com/ebms'/></tp:TransportSender>"
            + "    </tp:Transport>"
            + "    <tp:DocExchange tp:docExchangeId='dx1'>"
            + "      <tp:ebXMLSenderBinding>"
            + "        <tp:ReliableMessaging>"
            + "          <tp:Retries>3</tp:Retries>"
            + "          <tp:RetryInterval>PT5M</tp:RetryInterval>"
            + "        </tp:ReliableMessaging>"
            + "        <tp:SenderNonRepudiation/>"
            + "        <tp:SenderDigitalEnvelope/>"
            + "        <tp:PersistDuration>P1D</tp:PersistDuration>"
            + "      </tp:ebXMLSenderBinding>"
            + "    </tp:DocExchange>"
            + "  </tp:PartyInfo>"
            + "</tp:CollaborationProtocolAgreement>";

        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        CpaDeliveryChannelEntity c = channels.get(0);
        assertThat(c.getChannelId()).isEqualTo("ch1");
        assertThat(c.getPartyId()).isEqualTo(PARTY_A);
        assertThat(c.getEndpointUrl()).isEqualTo("https://party-a.example.com/ebms");
        assertThat(c.getRetryCount()).isEqualTo(3);
        assertThat(c.getRetryInterval()).isEqualTo(300);
        assertThat(c.getPersistDuration()).isEqualTo(86400);
        assertThat(c.getDkProfile()).isEqualTo("osb-rm-e");
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    void parseDeliveryChannels_nullOrBlank_returnsEmpty() {
        assertThat(parser.parseDeliveryChannels(null, CPA_ID)).isEmpty();
        assertThat(parser.parseDeliveryChannels("   ", CPA_ID)).isEmpty();
    }

    @Test
    void parseDeliveryChannels_malformedXml_returnsEmpty() {
        assertThat(parser.parseDeliveryChannels("<Root><Unclosed>", CPA_ID)).isEmpty();
    }

    @Test
    void parseDeliveryChannels_channelWithoutChannelId_isSkipped() {
        String xml = "<Root><PartyInfo><PartyId>" + PARTY_A + "</PartyId>"
            + "<DeliveryChannel transportId='t'/>"
            + "<Transport transportId='t'><TransportSender><Endpoint uri='http://x/'/></TransportSender></Transport>"
            + "</PartyInfo></Root>";
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID)).isEmpty();
    }

    @Test
    void parseDeliveryChannels_partyWithoutPartyId_isSkipped() {
        String xml = "<Root><PartyInfo><DeliveryChannel channelId='c'/></PartyInfo></Root>";
        assertThat(parser.parseDeliveryChannels(xml, CPA_ID)).isEmpty();
    }

    @Test
    void parseDeliveryChannels_unresolvedTransportRef_leavesEndpointNull() {
        String xml = "<Root><PartyInfo><PartyId>" + PARTY_A + "</PartyId>"
            + "<DeliveryChannel channelId='c' transportId='does-not-exist'/></PartyInfo></Root>";
        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getEndpointUrl()).isNull();
        assertThat(channels.get(0).getDkProfile()).isEqualTo("osb-be");
    }

    @Test
    void parseDeliveryChannels_invalidRetryIntervalOrDuration_leavesNull() {
        String xml = dxOnly("<ReliableMessaging><Retries>abc</Retries><RetryInterval>NOT_A_DURATION</RetryInterval></ReliableMessaging>");
        List<CpaDeliveryChannelEntity> channels = parser.parseDeliveryChannels(xml, CPA_ID);
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getRetryCount()).isNull();
        assertThat(channels.get(0).getRetryInterval()).isNull();
    }
}
