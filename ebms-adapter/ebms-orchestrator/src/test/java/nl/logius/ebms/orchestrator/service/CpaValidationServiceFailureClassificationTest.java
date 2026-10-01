package nl.logius.ebms.orchestrator.service;

import nl.logius.ebms.common.exception.EbmsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CpaValidationServiceFailureClassificationTest {

    private MockRestServiceServer server;
    private CpaValidationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cpa-service");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new CpaValidationService(builder.build());
    }

    @Test
    void certificateLookup_serverErrorIsRetryable() {
        server.expect(requestTo("http://cpa-service/api/cpa/cpa-1/certificates/party-1"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"unavailable\"}"));

        assertThatThrownBy(() -> service.getPartnerCertificates("cpa-1", "party-1"))
            .isInstanceOfSatisfying(EbmsException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("TEMPORARY_FAILURE"));
        server.verify();
    }

    @Test
    void certificateLookup_notFoundIsPermanent() {
        server.expect(requestTo("http://cpa-service/api/cpa/cpa-1/certificates/party-1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.getPartnerCertificates("cpa-1", "party-1"))
            .isInstanceOfSatisfying(EbmsException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("CERTIFICATE_NOT_FOUND"));
        server.verify();
    }
}