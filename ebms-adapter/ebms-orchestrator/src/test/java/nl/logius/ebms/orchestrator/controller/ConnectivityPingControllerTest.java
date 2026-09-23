package nl.logius.ebms.orchestrator.controller;

import nl.logius.ebms.orchestrator.dto.ConnectivityPingRequest;
import nl.logius.ebms.orchestrator.dto.ConnectivityPingResult;
import nl.logius.ebms.orchestrator.service.ConnectivityPingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectivityPingControllerTest {

    private final ConnectivityPingService service = mock(ConnectivityPingService.class);
    private final ConnectivityPingController controller = new ConnectivityPingController(service);

    @Test
    void ping_success_returnsHttp200() {
        when(service.ping("cpa-1", "from", "partner"))
            .thenReturn(ConnectivityPingResult.success(142));

        ResponseEntity<ConnectivityPingResult> response = controller.ping(
            "cpa-1", "partner", new ConnectivityPingRequest("from"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(ConnectivityPingResult.success(142));
    }

    @Test
    void ping_failure_returnsBadGatewayWithErrorDetail() {
        ConnectivityPingResult failure = ConnectivityPingResult.failure(
            87, "SSLHandshakeException: certificate_unknown", "CONNECTION_ERROR");
        when(service.ping("cpa-1", "from", "partner")).thenReturn(failure);

        ResponseEntity<ConnectivityPingResult> response = controller.ping(
            "cpa-1", "partner", new ConnectivityPingRequest("from"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isEqualTo(failure);
    }
}