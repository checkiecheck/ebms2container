package nl.logius.ebms.orchestrator.service;

import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.exception.XmlSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CryptoServiceClientFailureClassificationTest {

    private MockRestServiceServer server;
    private CryptoServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://crypto-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CryptoServiceClient(builder.build());
    }

    @Test
    void verify_serverErrorIsRetryable() {
        server.expect(requestTo("http://crypto-service/api/crypto/verify"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"internal detail\"}"));

        assertThatThrownBy(() -> client.verify("<signed/>", "message-1", "CPA-CERT"))
            .isInstanceOfSatisfying(EbmsException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("TEMPORARY_FAILURE"));
        server.verify();
    }

    @Test
    void verifyUnprocessableEntityRemainsPermanentSecurityFailure() {
        server.expect(requestTo("http://crypto-service/api/crypto/verify"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"detail\":\"signature invalid\"}"));

        assertThatThrownBy(() -> client.verify("<signed/>", "message-1", "CPA-CERT"))
            .isInstanceOfSatisfying(XmlSecurityException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("SecurityFailure"));
        server.verify();
    }
}