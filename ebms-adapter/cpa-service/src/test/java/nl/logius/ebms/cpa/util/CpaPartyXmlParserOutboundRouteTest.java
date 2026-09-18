package nl.logius.ebms.cpa.util;

import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CpaPartyXmlParserOutboundRouteTest {

    private final CpaPartyXmlParser parser = new CpaPartyXmlParser();

    @Test
    void parseOutboundRoutes_resolvesPartiesRolesActionAndChannelThroughIdRef() {
        String xml = """
            <tp:CollaborationProtocolAgreement xmlns:tp="urn:oasis:names:tc:ebxml-cppa:schema:xsd:2.0">
              <tp:PartyInfo>
                <tp:PartyId>sender</tp:PartyId>
                <tp:DeliveryChannel tp:channelId="sender-channel"/>
                <tp:CollaborationRole>
                  <tp:Role tp:name="InitiatorRole"/>
                  <tp:ServiceBinding>
                    <tp:Service tp:type="urn:test:service-type">urn:test:service</tp:Service>
                    <tp:CanSend>
                      <tp:ThisPartyActionBinding tp:id="send-submit" tp:action="Submit" tp:packageId="pkg">
                        <tp:BusinessTransactionCharacteristics/>
                        <tp:ChannelId>sender-channel</tp:ChannelId>
                      </tp:ThisPartyActionBinding>
                      <tp:OtherPartyActionBinding>receive-submit</tp:OtherPartyActionBinding>
                    </tp:CanSend>
                  </tp:ServiceBinding>
                </tp:CollaborationRole>
              </tp:PartyInfo>
              <tp:PartyInfo>
                <tp:PartyId>receiver</tp:PartyId>
                <tp:DeliveryChannel tp:channelId="receiver-channel" tp:docExchangeId="receiver-dx"/>
                <tp:DocExchange tp:docExchangeId="receiver-dx">
                  <tp:SenderNonRepudiation>
                    <tp:HashFunction>SHA-1</tp:HashFunction>
                    <tp:SignatureAlgorithm>RSA-SHA1</tp:SignatureAlgorithm>
                  </tp:SenderNonRepudiation>
                </tp:DocExchange>
                <tp:CollaborationRole>
                  <tp:Role tp:name="ResponderROLE"/>
                  <tp:ServiceBinding>
                    <tp:Service tp:type="urn:test:service-type">urn:test:service</tp:Service>
                    <tp:CanReceive>
                      <tp:ThisPartyActionBinding tp:id="receive-submit" tp:action="Submit" tp:packageId="pkg">
                        <tp:BusinessTransactionCharacteristics/>
                        <tp:ChannelId>receiver-channel</tp:ChannelId>
                      </tp:ThisPartyActionBinding>
                    </tp:CanReceive>
                  </tp:ServiceBinding>
                </tp:CollaborationRole>
              </tp:PartyInfo>
            </tp:CollaborationProtocolAgreement>
            """;

        List<CpaOutboundRouteEntity> routes = parser.parseOutboundRoutes(xml, "cpa-1");

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.getCpaId()).isEqualTo("cpa-1");
            assertThat(route.getFromPartyId()).isEqualTo("sender");
            assertThat(route.getToPartyId()).isEqualTo("receiver");
            assertThat(route.getService()).isEqualTo("urn:test:service");
            assertThat(route.getServiceType()).isEqualTo("urn:test:service-type");
            assertThat(route.getAction()).isEqualTo("Submit");
            assertThat(route.getActionBindingId()).isEqualTo("send-submit");
            assertThat(route.isSignatureRequired()).isTrue();
            assertThat(route.getHashFunction())
              .isEqualTo("http://www.w3.org/2000/09/xmldsig#sha1");
            assertThat(route.getSignatureAlgorithm())
              .isEqualTo("http://www.w3.org/2000/09/xmldsig#rsa-sha1");
            assertThat(route.getFromRole()).isEqualTo("InitiatorRole");
            assertThat(route.getToRole()).isEqualTo("ResponderROLE");
            assertThat(route.getChannelPartyId()).isEqualTo("receiver");
            assertThat(route.getChannelId()).isEqualTo("receiver-channel");
        });
    }

    @Test
    void parseOutboundRoutes_normalizesXmldsigMoreSha256DigestToXmlencSha256() {
        String xml = """
            <tp:CollaborationProtocolAgreement xmlns:tp="urn:oasis:names:tc:ebxml-cppa:schema:xsd:2.0">
              <tp:PartyInfo>
                <tp:PartyId>sender</tp:PartyId>
                <tp:DeliveryChannel tp:channelId="sender-channel"/>
                <tp:CollaborationRole>
                  <tp:Role tp:name="InitiatorRole"/>
                  <tp:ServiceBinding>
                    <tp:Service>urn:test:service</tp:Service>
                    <tp:CanSend>
                      <tp:ThisPartyActionBinding tp:id="send-submit" tp:action="Submit">
                        <tp:ChannelId>sender-channel</tp:ChannelId>
                      </tp:ThisPartyActionBinding>
                      <tp:OtherPartyActionBinding>receive-submit</tp:OtherPartyActionBinding>
                    </tp:CanSend>
                  </tp:ServiceBinding>
                </tp:CollaborationRole>
              </tp:PartyInfo>
              <tp:PartyInfo>
                <tp:PartyId>receiver</tp:PartyId>
                <tp:DeliveryChannel tp:channelId="receiver-channel" tp:docExchangeId="receiver-dx"/>
                <tp:DocExchange tp:docExchangeId="receiver-dx">
                  <tp:SenderNonRepudiation>
                    <tp:HashFunction>http://www.w3.org/2001/04/xmldsig-more#sha256</tp:HashFunction>
                    <tp:SignatureAlgorithm>RSA-SHA256</tp:SignatureAlgorithm>
                  </tp:SenderNonRepudiation>
                </tp:DocExchange>
                <tp:CollaborationRole>
                  <tp:Role tp:name="ResponderROLE"/>
                  <tp:ServiceBinding>
                    <tp:Service>urn:test:service</tp:Service>
                    <tp:CanReceive>
                      <tp:ThisPartyActionBinding tp:id="receive-submit" tp:action="Submit">
                        <tp:ChannelId>receiver-channel</tp:ChannelId>
                      </tp:ThisPartyActionBinding>
                    </tp:CanReceive>
                  </tp:ServiceBinding>
                </tp:CollaborationRole>
              </tp:PartyInfo>
            </tp:CollaborationProtocolAgreement>
            """;

        List<CpaOutboundRouteEntity> routes = parser.parseOutboundRoutes(xml, "cpa-1");

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.getHashFunction())
                .isEqualTo("http://www.w3.org/2001/04/xmlenc#sha256");
            assertThat(route.getSignatureAlgorithm())
                .isEqualTo("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256");
        });
    }
}