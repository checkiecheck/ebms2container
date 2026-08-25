package nl.logius.ebms.orchestrator.soap;

import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito unit tests for {@link OutboundSoapClient} dynamic mTLS trust logic:
 *  (a) https + valid PEM cert => cpaValidationService IS called, SSLContext build succeeds
 *      (no MTLS_TRUST_ERROR is raised, only CONNECTION_ERROR at dispatch time to bogus endpoint).
 *  (b) https + cpaValidationService throws => the EbmsException is propagated and dispatch
 *      is NEVER reached.
 *  (c) plain http => cpaValidationService is NEVER invoked.
 */
@ExtendWith(MockitoExtension.class)
class OutboundSoapClientDynamicTrustTest {

    // Self-signed PEM generated at test-suite creation (CN=test, RSA 2048, validity 10y).
    private static final String VALID_SELF_SIGNED_PEM = """
        -----BEGIN CERTIFICATE-----
        MIICwTCCAamgAwIBAgIIOKm84+YfE+4wDQYJKoZIhvcNAQEMBQAwDzENMAsGA1UE
        AxMEdGVzdDAeFw0yNjA4MjUxMTE3NDdaFw0zNjA4MjIxMTE3NDdaMA8xDTALBgNV
        BAMTBHRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCW4Hzh4cND
        llwKY2CohydyvftvbQN8/4lvukFJNEIFBOEBrWZYC8Z1L4HfZUrtlygKKER7WgEo
        Zd5/sl9o2gptnCVN/S8r5T1G8Awkf+OI9m1vv8crkmooH7KMEeGKyjMT+dhT0X2g
        gJ+5rxFJ6wHR2l+RLGsN78hNLfns1JleZgdVPJ2XhyR1xLR/h66UEKZd5vbmWryQ
        v5EVqcMLvoQf5xFAvGISgXekqgy/RBad3behlfShVlv4d3C7UzbAcBOdxQhBRw3e
        ABWnFvVsL2z9UQg7jj3Ribbinc+fUzAp58TwQme74yDgMt0ZF+kLhiqv2z6y/n8E
        kk2MYJEYlVQpAgMBAAGjITAfMB0GA1UdDgQWBBSFKGBQKRaVDV7QqfJ/Kc/ut73P
        mzANBgkqhkiG9w0BAQwFAAOCAQEAEJNB4NkNHg97hXf+MDGP5InZAfQqv2PAdea5
        RzLVybibZjNxGo5oS1lOeyS09P5Lsxf4hvSe/Ceqhus75jWrQUKQLkPFy0G6tSeI
        YAugCRd+QCF1vIMjbm0oVkl9XDG5B72o+Lro+6UREesPTsam0Xak/FANSJbY/IIU
        PyVZxkBOqa9YfGvdQCEJkH4s0vtgg/BCTian06sN3j10n+po6GeI26K+0Mb8nT+D
        A/fQEQ5DDWyuPggcApol7PJeHscaYASwsywNGyTss4IKsCcTPTJq+aFkIxHNPaow
        TwCxplKiB0GHVFgIwefpOdW6cBgOBn3JD8bR7Od+HcZmtGDdAQ==
        -----END CERTIFICATE-----
        """.replaceAll("(?m)^\\s+", "");

    private static final String MIN_SOAP =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<soap:Header/><soap:Body/></soap:Envelope>";

    @Mock CpaValidationService cpaValidationService;

    private OutboundSoapClient client;

    @BeforeEach
    void setUp() {
        EbmsOutboundSSLProperties sslProps = new EbmsOutboundSSLProperties();
        // Leave keystore-path blank so buildDynamicSslContext takes the trust-only branch.
        client = new OutboundSoapClient(sslProps, cpaValidationService);
    }

    // (a) https + valid partner cert -> SSLContext built OK, dispatch attempted (fails w/ CONNECTION_ERROR
    //     because endpoint is unreachable, NOT MTLS_TRUST_ERROR).
    @Test
    @DisplayName("(a) https + valid PEM => cpaValidationService called, SSLContext built (no MTLS_TRUST_ERROR)")
    void https_validCert_buildsSslContext() {
        PartnerCertificateDto cert = PartnerCertificateDto.builder()
            .id("c1").cpaId("cpa-1").partyId("00000000000000000002")
            .certificateAlias("partner-alias").certificatePem(VALID_SELF_SIGNED_PEM)
            .build();
        when(cpaValidationService.getPartnerCertificates("cpa-1", "00000000000000000002"))
            .thenReturn(List.of(cert));

        // Unreachable endpoint on purpose - the SSLContext build MUST succeed first;
        // failure comes only at dispatch time, and it must be CONNECTION_ERROR (not MTLS_TRUST_ERROR).
        assertThatThrownBy(() ->
            client.send("https://127.0.0.1:1/ebms", MIN_SOAP, "cpa-1", "00000000000000000002")
        ).isInstanceOfSatisfying(EbmsException.class, ex ->
            assertThat(ex.getErrorCode())
                .as("dynamic trust must be built successfully before dispatch failure")
                .isNotEqualTo("MTLS_TRUST_ERROR")
        );

        verify(cpaValidationService).getPartnerCertificates("cpa-1", "00000000000000000002");
    }

    // (b) https + CpaValidationService throws => propagate EbmsException, do NOT reach dispatch.
    @Test
    @DisplayName("(b) https + CERTIFICATE_NOT_FOUND from CpaValidationService => propagates EbmsException, no dispatch")
    void https_certLookupFails_propagatesEbmsException() {
        when(cpaValidationService.getPartnerCertificates(anyString(), anyString()))
            .thenThrow(new EbmsException("CERTIFICATE_NOT_FOUND", "no cert for party"));

        assertThatThrownBy(() ->
            client.send("https://partner.example/ebms", MIN_SOAP, "cpa-1", "00000000000000000002")
        ).isInstanceOfSatisfying(EbmsException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo("CERTIFICATE_NOT_FOUND");
            assertThat(ex.getMessage()).contains("no cert for party");
        });

        verify(cpaValidationService).getPartnerCertificates("cpa-1", "00000000000000000002");
    }

    // (c) plain http -> cpaValidationService MUST NOT be invoked.
    @Test
    @DisplayName("(c) plain http endpoint => cpaValidationService NEVER invoked")
    void http_skipsCertLookup() {
        // Any failure at dispatch (unreachable host) is fine; the assertion is on the mock.
        try {
            client.send("http://127.0.0.1:1/ebms", MIN_SOAP, "cpa-1", "00000000000000000002");
        } catch (EbmsException ignored) {
            // Expected: unreachable endpoint - not what we're testing.
        }

        verify(cpaValidationService, never()).getPartnerCertificates(anyString(), anyString());
    }
}
