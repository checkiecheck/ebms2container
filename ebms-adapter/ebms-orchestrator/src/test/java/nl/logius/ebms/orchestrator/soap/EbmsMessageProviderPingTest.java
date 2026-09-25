package nl.logius.ebms.orchestrator.soap;

import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.handler.MessageContext;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.service.PingSendingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbmsMessageProviderPingTest {

    @Mock OrchestratorService orchestratorService;
    @Mock PingEchoService pingEchoService;
    @Mock PingSendingService pingSendingService;
    @Mock CpaValidationService cpaValidationService;
    @Mock WebServiceContext webServiceContext;
    @Mock MessageContext messageContext;

    private EbmsMessageProvider provider;
    private SoapHelper soapHelper;
    private Map<String, Object> responseContext;

    @BeforeEach
    void setUp() {
        soapHelper = new SoapHelper();
        provider = new EbmsMessageProvider(
            orchestratorService, pingEchoService, pingSendingService, cpaValidationService, soapHelper);
        responseContext = new HashMap<>();
        when(webServiceContext.getMessageContext()).thenReturn(messageContext);
        when(messageContext.get(MessageContext.HTTP_REQUEST_HEADERS)).thenReturn(null);
        ReflectionTestUtils.setField(provider, "wsContext", webServiceContext);
    }

    @Test
    void asyncCpa_publishesPongAndReturnsHttp204() throws Exception {
        when(cpaValidationService.getDeliveryChannel(
            "CPAID_EchoService-1-0-HTTPS", "00000004003214345001"))
            .thenReturn(DeliveryChannelDto.builder().syncReplyMode("none").build());
        when(messageContext.put(MessageContext.HTTP_RESPONSE_CODE, 204)).thenAnswer(invocation -> {
            responseContext.put(MessageContext.HTTP_RESPONSE_CODE, 204);
            return null;
        });

        SOAPMessage response = provider.invoke(soapHelper.soapFromString(pingXml()));

        assertThat(response).isNull();
        assertThat(responseContext.get(MessageContext.HTTP_RESPONSE_CODE)).isEqualTo(204);
        verify(pingSendingService).sendAsyncPong(any());
    }

    @Test
    void cpaLookupFailure_preservesSynchronousPong() throws Exception {
        SOAPMessage pong = mock(SOAPMessage.class);
        when(cpaValidationService.getDeliveryChannel(
            "CPAID_EchoService-1-0-HTTPS", "00000004003214345001"))
            .thenThrow(new RuntimeException("cpa-service unavailable"));
        when(pingEchoService.handlePing(any())).thenReturn(pong);

        SOAPMessage response = provider.invoke(soapHelper.soapFromString(pingXml()));

        assertThat(response).isSameAs(pong);
        verify(pingSendingService, org.mockito.Mockito.never()).sendAsyncPong(any());
        verify(pingEchoService).handlePing(any());
    }

    private String pingXml() {
        return """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
              <SOAP-ENV:Header>
                <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                  <eb:From><eb:PartyId>00000004003214345001</eb:PartyId></eb:From>
                  <eb:To><eb:PartyId>00000002003214345001</eb:PartyId></eb:To>
                  <eb:CPAId>CPAID_EchoService-1-0-HTTPS</eb:CPAId>
                  <eb:ConversationId>conversation-1</eb:ConversationId>
                  <eb:Service>urn:oasis:names:tc:ebxml-msg:service</eb:Service>
                  <eb:Action>Ping</eb:Action>
                  <eb:MessageData>
                    <eb:MessageId>ping-1@sender</eb:MessageId>
                    <eb:Timestamp>2026-09-25T06:35:02Z</eb:Timestamp>
                  </eb:MessageData>
                </eb:MessageHeader>
              </SOAP-ENV:Header>
              <SOAP-ENV:Body/>
            </SOAP-ENV:Envelope>
            """;
    }
}
