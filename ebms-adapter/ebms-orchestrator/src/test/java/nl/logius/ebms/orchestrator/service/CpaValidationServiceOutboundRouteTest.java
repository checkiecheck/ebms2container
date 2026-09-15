package nl.logius.ebms.orchestrator.service;

import nl.logius.ebms.common.model.cpa.OutboundRouteDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CpaValidationServiceOutboundRouteTest {

    private MockRestServiceServer server;
    private CpaValidationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cpa-service");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new CpaValidationService(builder.build());
    }

    @Test
    void getOutboundRoute_encodesLookupValuesAndPreservesRoleCase() {
        server.expect(requestTo("http://cpa-service/api/cpa/cpa-1/outbound-route"
                + "?fromPartyId=sender&toPartyId=receiver"
            + "&service=urn%3Atest%3Aservice%2Bv1%23fragment"
                + "&serviceType=urn%3Atest%3Atype"
            + "&action=Submit%26Audit%3DNow"
                + "&fromRole=InitiatorRole&toRole=ResponderROLE"))
            .andRespond(withSuccess("""
                {
                  "cpaId":"cpa-1",
                  "fromRole":"InitiatorRole",
                  "toRole":"ResponderROLE",
                  "channel":{"endpointUrl":"https://partner.example/ebms","dkProfile":"osb-be"}
                }
                """, MediaType.APPLICATION_JSON));

        OutboundRouteDto route = service.getOutboundRoute(
            "cpa-1", "sender", "receiver", "urn:test:service+v1#fragment", "urn:test:type",
            "Submit&Audit=Now",
            "InitiatorRole", "ResponderROLE");

        assertThat(route.getFromRole()).isEqualTo("InitiatorRole");
        assertThat(route.getToRole()).isEqualTo("ResponderROLE");
        assertThat(route.getChannel().getEndpointUrl()).isEqualTo("https://partner.example/ebms");
        server.verify();
    }
}