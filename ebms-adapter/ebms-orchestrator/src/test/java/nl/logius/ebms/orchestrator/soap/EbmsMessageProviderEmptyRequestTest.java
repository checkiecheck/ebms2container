package nl.logius.ebms.orchestrator.soap;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.handler.MessageContext;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.service.PingSendingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EbmsMessageProviderEmptyRequestTest {

    private final OrchestratorService orchestratorService = mock(OrchestratorService.class);
    private final MessageContext messageContext = mock(MessageContext.class);
    private final HttpServletResponse servletResponse = mock(HttpServletResponse.class);
    private EbmsMessageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EbmsMessageProvider(orchestratorService, mock(PingEchoService.class),
            mock(PingSendingService.class), mock(CpaValidationService.class), new SoapHelper());
        WebServiceContext wsContext = mock(WebServiceContext.class);
        when(wsContext.getMessageContext()).thenReturn(messageContext);
        when(messageContext.get(MessageContext.SERVLET_RESPONSE)).thenReturn(servletResponse);
        ReflectionTestUtils.setField(provider, "wsContext", wsContext);
    }

    @Test
    void get_returns405WithAllowHeaderAndCommitsResponse() throws Exception {
        when(messageContext.get(MessageContext.HTTP_REQUEST_METHOD)).thenReturn("GET");

        assertThat(provider.invoke(null)).isNull();

        verify(messageContext).put(MessageContext.HTTP_RESPONSE_CODE, 405);
        verify(servletResponse).setStatus(405);
        verify(servletResponse).setHeader("Allow", "POST");
        verify(servletResponse).flushBuffer();
        verifyNoInteractions(orchestratorService);
    }

    @Test
    void emptyPost_returns400() throws Exception {
        when(messageContext.get(MessageContext.HTTP_REQUEST_METHOD)).thenReturn("POST");

        assertThat(provider.invoke(null)).isNull();

        verify(servletResponse).setStatus(400);
        verify(servletResponse).flushBuffer();
    }
}
