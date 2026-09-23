package nl.logius.ebms.orchestrator.service;

import jakarta.xml.soap.SOAPMessage;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.EbxmlMessageHeader;
import nl.logius.ebms.orchestrator.dto.ConnectivityPingResult;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
import nl.logius.ebms.orchestrator.soap.PingEchoService;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectivityPingServiceTest {

    private static final String CPA_ID = "cpa-1";
    private static final String FROM_PARTY = "00000000000000000001";
    private static final String TO_PARTY = "00000000000000000002";
    private static final String ENDPOINT = "https://partner.example/ebms";

    private final CpaValidationService cpaValidationService = mock(CpaValidationService.class);
    private final OutboundSoapClient outboundSoapClient = mock(OutboundSoapClient.class);
    private final SoapHelper soapHelper = new SoapHelper();
    private ConnectivityPingService service;

    @BeforeEach
    void setUp() {
        service = new ConnectivityPingService(cpaValidationService, outboundSoapClient, soapHelper);
        when(cpaValidationService.getDeliveryChannel(CPA_ID, TO_PARTY)).thenReturn(
            DeliveryChannelDto.builder().endpointUrl(ENDPOINT).build());
    }

    @Test
    void ping_validPong_returnsSuccessWithLatency() {
        when(outboundSoapClient.send(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> pongFor(invocation.getArgument(1), false, false));

        ConnectivityPingResult result = service.ping(CPA_ID, FROM_PARTY, TO_PARTY);

        assertThat(result.success()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.message()).isEqualTo("Pong ontvangen");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void ping_pongWithWrongCorrelation_returnsInvalidPong() {
        when(outboundSoapClient.send(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> pongFor(invocation.getArgument(1), true, false));

        ConnectivityPingResult result = service.ping(CPA_ID, FROM_PARTY, TO_PARTY);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_PONG");
        assertThat(result.message()).contains("RefToMessageId");
    }

    @Test
    void ping_responseWithWrongAction_returnsInvalidPong() {
        when(outboundSoapClient.send(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> pongFor(invocation.getArgument(1), false, true));

        ConnectivityPingResult result = service.ping(CPA_ID, FROM_PARTY, TO_PARTY);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_PONG");
        assertThat(result.message()).contains("geen geldige ebMS Pong");
    }

    @Test
    void ping_sslHandshakeFailure_preservesExactCause() {
        when(outboundSoapClient.send(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new EbmsException("CONNECTION_ERROR",
                "SOAP-verzending mislukt: SSLHandshakeException: PKIX path building failed"));

        ConnectivityPingResult result = service.ping(CPA_ID, FROM_PARTY, TO_PARTY);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONNECTION_ERROR");
        assertThat(result.message()).contains("SSLHandshakeException", "PKIX path building failed");
    }

    private SOAPMessage pongFor(String pingXml, boolean wrongCorrelation, boolean wrongAction) throws Exception {
        SOAPMessage ping = soapHelper.soapFromString(pingXml);
        EbxmlMessageHeader pingHeader = soapHelper.parseMessageHeader(ping.getSOAPHeader());
        assertThat(pingHeader.getService().getValue()).isEqualTo(SoapHelper.EBXML_PING_SERVICE);
        assertThat(pingHeader.getAction()).isEqualTo("Ping");
        assertThat(pingHeader.getFrom().get(0).getValue()).isEqualTo(FROM_PARTY);
        assertThat(pingHeader.getTo().get(0).getValue()).isEqualTo(TO_PARTY);
        if (wrongCorrelation) {
            pingHeader.getMessageInfo().setMessageId("different-message-id");
        }
        SOAPMessage pong = new PingEchoService(soapHelper).handlePing(pingHeader);
        if (wrongAction) {
            pong.getSOAPHeader().getElementsByTagNameNS(SoapHelper.EBXML_MSG_NS, "Action")
                .item(0).setTextContent("Acknowledgment");
            pong.saveChanges();
        }
        return pong;
    }
}