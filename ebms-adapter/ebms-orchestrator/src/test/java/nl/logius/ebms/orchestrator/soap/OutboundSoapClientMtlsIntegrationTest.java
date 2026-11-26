package nl.logius.ebms.orchestrator.soap;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.soap.TestPki.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Echte TLS-handshakes tegen een lokale {@link HttpsServer}: bewijst dat het servercertificaat
 * tegen de truststore wordt gevalideerd (niet tegen het CPA-partnercertificaat) en dat het
 * clientcertificaat dynamisch uit de CPA/keystore wordt gekozen.
 */
class OutboundSoapClientMtlsIntegrationTest {

    private static final String CPA = "cpa-1";
    private static final String OWN = "00000001851407742000";
    private static final String PARTNER = "00000004003214345001";
    private static final String SOAP =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Header/><soap:Body/></soap:Envelope>";

    @TempDir Path dir;

    private HttpsServer server;
    private final AtomicReference<X509Certificate[]> presentedClientChain = new AtomicReference<>();
    private CpaValidationService cpa;
    private EbmsOutboundSSLProperties props;
    private Identity serverCa;
    private Identity clientCa;
    private Identity signing;
    private Identity orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        cpa = mock(CpaValidationService.class);
        serverCa = TestPki.newCa("server-ca");
        clientCa = TestPki.newCa("client-ca");
        signing = TestPki.client(clientCa, "signing", OWN);
        orchestrator = TestPki.client(clientCa, "orchestrator", OWN);

        props = new EbmsOutboundSSLProperties();
        props.setKeystorePath(TestPki.write(
            TestPki.keyStore(Map.of("signing-key", signing, "orchestrator-key", orchestrator), Map.of()),
            dir.resolve("keystore.p12")).toString());
        props.setKeystorePassword(new String(TestPki.PASSWORD));
        trustServerCa(serverCa);
        props.setIncludeJvmDefaultTrust(false);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── Server-TLS: validatie tegen truststore, niet tegen CPA-cert ───────────

    @Test
    void serverCertificateFromTruststoreCa_differentFromCpaCert_succeeds() throws Exception {
        startServer(TestPki.server(serverCa, "api", "127.0.0.1"), false);
        Identity unrelatedCpaCert = TestPki.selfSigned("cpa-partner");
        when(cpa.getPartnerCertificates(CPA, PARTNER)).thenReturn(List.of(dto(unrelatedCpaCert.cert())));

        assertThat(send()).isNotNull();
        verify(cpa, never()).getPartnerCertificates(CPA, PARTNER);
    }

    @Test
    void serverCertificateFromUnknownCa_isRejected() throws Exception {
        startServer(TestPki.server(TestPki.newCa("rogue-ca"), "api", "127.0.0.1"), false);

        assertThatThrownBy(this::send)
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("CONNECTION_ERROR"))
            .hasMessageContaining("certification path");
    }

    @Test
    void hostnameMismatch_isRejectedEvenWhenCaIsTrusted() throws Exception {
        startServer(TestPki.server(serverCa, "api", "other.example"), false);

        assertThatThrownBy(this::send)
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("CONNECTION_ERROR"));
    }

    @Test
    void selfSignedServerEqualToCpaCert_onlyTrustedWhenTrustCpaCertificatesEnabled() throws Exception {
        Identity selfSigned = TestPki.selfSigned("test-server", "127.0.0.1");
        startServer(selfSigned, false);
        when(cpa.getPartnerCertificates(CPA, PARTNER)).thenReturn(List.of(dto(selfSigned.cert())));

        assertThatThrownBy(this::send).isInstanceOf(EbmsException.class);

        props.setTrustCpaCertificates(true);
        assertThat(send()).isNotNull();
    }

    @Test
    void jvmDefaultTrust_isUsedWhenEnabled() throws Exception {
        Path jvmTrust = TestPki.write(TestPki.trustStore(serverCa.cert()), dir.resolve("jvm-trust.p12"));
        props.setTruststorePath(null);
        startServer(TestPki.server(serverCa, "api", "127.0.0.1"), false);

        String[] keys = {"javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = System.getProperty(keys[i]);
        }
        System.setProperty(keys[0], jvmTrust.toString());
        System.setProperty(keys[1], new String(TestPki.PASSWORD));
        System.setProperty(keys[2], "PKCS12");
        try {
            props.setIncludeJvmDefaultTrust(true);
            assertThat(send()).isNotNull();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    // ── Client-authenticatie: certificaat uit CPA → keystore-alias ────────────

    @Test
    void clientAuth_presentsKeystoreKeyMatchingOwnCpaCertificate() throws Exception {
        startServer(TestPki.server(serverCa, "api", "127.0.0.1"), true);
        // Fallback-alias is 'orchestrator-key', maar de CPA noemt het signing-certificaat.
        when(cpa.getPartnerCertificates(CPA, OWN)).thenReturn(List.of(dto(signing.cert())));

        assertThat(send()).isNotNull();
        assertThat(presentedClientChain.get()[0]).isEqualTo(signing.cert());
    }

    @Test
    void clientAuth_withoutOwnCpaCertificate_usesConfiguredFallbackAlias() throws Exception {
        startServer(TestPki.server(serverCa, "api", "127.0.0.1"), true);
        when(cpa.getPartnerCertificates(CPA, OWN)).thenThrow(new EbmsException("CERTIFICATE_NOT_FOUND", "geen cert"));

        assertThat(send()).isNotNull();
        assertThat(presentedClientChain.get()[0]).isEqualTo(orchestrator.cert());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void trustServerCa(Identity ca) throws Exception {
        props.setTruststorePath(TestPki.write(TestPki.trustStore(ca.cert()), dir.resolve("truststore.p12")).toString());
        props.setTruststorePassword(new String(TestPki.PASSWORD));
    }

    private jakarta.xml.soap.SOAPMessage send() {
        OutboundSoapClient client = new OutboundSoapClient(
            new OutboundSslContextFactory(props, cpa, "orchestrator-key", "signing-key"), new SoapHelper());
        return client.send("https://127.0.0.1:" + server.getAddress().getPort() + "/ebms", SOAP, CPA, OWN, PARTNER);
    }

    private static PartnerCertificateDto dto(X509Certificate cert) throws Exception {
        return PartnerCertificateDto.builder().certificateAlias("cert").certificatePem(TestPki.pem(cert)).build();
    }

    private void startServer(Identity identity, boolean requireClientAuth) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(TestPki.keyStore(Map.of("server", identity), Map.of()), TestPki.PASSWORD);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(TestPki.trustStore(clientCa.cert()));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(requireClientAuth);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/ebms", exchange -> {
            if (requireClientAuth) {
                try {
                    presentedClientChain.set((X509Certificate[]) ((HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates());
                } catch (Exception ignored) {
                    // geen clientcertificaat gepresenteerd
                }
            }
            byte[] body = SOAP.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }
}
