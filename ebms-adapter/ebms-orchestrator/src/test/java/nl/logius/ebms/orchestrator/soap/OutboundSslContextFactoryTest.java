package nl.logius.ebms.orchestrator.soap;

import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.soap.TestPki.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboundSslContextFactoryTest {

    private static final String CPA = "cpa-1";
    private static final String OWN = "00000001851407742000";
    private static final String PARTNER = "00000004003214345001";

    @TempDir Path dir;

    private CpaValidationService cpa;
    private EbmsOutboundSSLProperties props;
    private Identity ca;
    private Identity signing;
    private Identity orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        cpa = mock(CpaValidationService.class);
        ca = TestPki.newCa("client-ca");
        signing = TestPki.client(ca, "signing", OWN);
        orchestrator = TestPki.client(ca, "orchestrator", OWN);

        props = new EbmsOutboundSSLProperties();
        props.setKeystorePath(writeKeystore(Map.of("signing-key", signing, "orchestrator-key", orchestrator)).toString());
        props.setKeystorePassword(new String(TestPki.PASSWORD));
        props.setIncludeJvmDefaultTrust(true);
    }

    private OutboundSslContextFactory factory() {
        return new OutboundSslContextFactory(props, cpa, "orchestrator-key", "signing-key");
    }

    private Path writeKeystore(Map<String, Identity> entries) throws Exception {
        return TestPki.write(TestPki.keyStore(new LinkedHashMap<>(entries), Map.of()), dir.resolve("keystore.p12"));
    }

    private static PartnerCertificateDto dto(X509Certificate cert) throws Exception {
        return PartnerCertificateDto.builder().certificateAlias("cert").certificatePem(TestPki.pem(cert)).build();
    }

    private KeyStore loadKeystore() throws Exception {
        return KeyStore.getInstance(new java.io.File(props.getKeystorePath()), TestPki.PASSWORD);
    }

    @Test
    void selectAlias_matchesCpaCertificateOverFallbackAlias() throws Exception {
        // CPA noemt het signing-cert; fallback-alias 'orchestrator-key' mag dan niet winnen.
        assertThat(factory().selectAlias(loadKeystore(), List.of(signing.cert()))).isEqualTo("signing-key");
    }

    @Test
    void selectAlias_sameCertUnderTwoAliases_prefersConfiguredClientAlias() throws Exception {
        props.setKeystorePath(writeKeystore(Map.of("a-signing", signing, "orchestrator-key", signing)).toString());

        assertThat(factory().selectAlias(loadKeystore(), List.of(signing.cert()))).isEqualTo("orchestrator-key");
    }

    @Test
    void selectAlias_noCpaCert_usesClientAliasThenSigningAlias() throws Exception {
        assertThat(factory().selectAlias(loadKeystore(), List.of())).isEqualTo("orchestrator-key");

        props.setKeystorePath(writeKeystore(Map.of("signing-key", signing)).toString());
        assertThat(factory().selectAlias(loadKeystore(), List.of())).isEqualTo("signing-key");
    }

    @Test
    void selectAlias_noMatchAndNoFallbackAlias_failsWithKeyError() throws Exception {
        props.setKeystorePath(writeKeystore(Map.of("other", signing)).toString());

        assertThatThrownBy(() -> factory().selectAlias(loadKeystore(), List.of()))
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("MTLS_KEY_ERROR"));
    }

    @Test
    void getSslContext_ownCertNotFound_stillBuildsContextFromFallbackAlias() {
        when(cpa.getPartnerCertificates(CPA, OWN)).thenThrow(new EbmsException("CERTIFICATE_NOT_FOUND", "geen cert"));

        assertThat(factory().getSslContext(CPA, OWN, PARTNER)).isNotNull();
        verify(cpa, never()).getPartnerCertificates(CPA, PARTNER);
    }

    @Test
    void getSslContext_temporaryCpaFailure_isPropagated() {
        when(cpa.getPartnerCertificates(CPA, OWN)).thenThrow(new EbmsException("TEMPORARY_FAILURE", "cpa down"));

        assertThatThrownBy(() -> factory().getSslContext(CPA, OWN, PARTNER))
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TEMPORARY_FAILURE"));
    }

    @Test
    void getSslContext_partnerCertificateOnlyLookedUpWhenTrustCpaEnabled() throws Exception {
        Identity partner = TestPki.selfSigned("partner");
        when(cpa.getPartnerCertificates(CPA, OWN)).thenReturn(List.of(dto(signing.cert())));
        when(cpa.getPartnerCertificates(CPA, PARTNER)).thenReturn(List.of(dto(partner.cert())));

        factory().getSslContext(CPA, OWN, PARTNER);
        verify(cpa, never()).getPartnerCertificates(CPA, PARTNER);

        props.setTrustCpaCertificates(true);
        factory().getSslContext(CPA, OWN, PARTNER);
        verify(cpa).getPartnerCertificates(CPA, PARTNER);
    }

    @Test
    void getSslContext_isCachedPerCertificateSet() throws Exception {
        when(cpa.getPartnerCertificates(CPA, OWN)).thenReturn(List.of(dto(signing.cert())));
        OutboundSslContextFactory factory = factory();

        SSLContext first = factory.getSslContext(CPA, OWN, PARTNER);
        assertThat(factory.getSslContext(CPA, OWN, PARTNER)).isSameAs(first);

        when(cpa.getPartnerCertificates(CPA, OWN)).thenReturn(List.of(dto(orchestrator.cert())));
        assertThat(factory.getSslContext(CPA, OWN, PARTNER)).isNotSameAs(first);
        verify(cpa, times(3)).getPartnerCertificates(CPA, OWN);
    }

    @Test
    void getSslContext_rebuiltWhenKeystoreFileChanges() throws Exception {
        OutboundSslContextFactory factory = factory();
        SSLContext first = factory.getSslContext(CPA, OWN, PARTNER);

        Path keystore = writeKeystore(Map.of("orchestrator-key", orchestrator));
        assertThat(keystore.toFile().setLastModified(System.currentTimeMillis() + 5_000)).isTrue();

        assertThat(factory.getSslContext(CPA, OWN, PARTNER)).isNotSameAs(first);
    }

    @Test
    void getSslContext_oinMismatch_warnsByDefaultAndFailsWhenEnforced() {
        assertThat(factory().getSslContext(CPA, PARTNER, PARTNER)).isNotNull();

        props.setEnforceClientOinMatch(true);
        assertThatThrownBy(() -> factory().getSslContext(CPA, PARTNER, PARTNER))
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("MTLS_KEY_ERROR"));
    }

    @Test
    void getSslContext_noTrustSource_failsWithTrustError() {
        props.setIncludeJvmDefaultTrust(false);
        props.setTruststorePath(dir.resolve("missing.p12").toString());

        assertThatThrownBy(() -> factory().getSslContext(CPA, OWN, PARTNER))
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("MTLS_TRUST_ERROR"));
    }

    @Test
    void getSslContext_corruptTruststore_failsWithTrustError() throws Exception {
        Path corrupt = dir.resolve("truststore.p12");
        java.nio.file.Files.writeString(corrupt, "geen pkcs12");
        props.setTruststorePath(corrupt.toString());
        props.setTruststorePassword(new String(TestPki.PASSWORD));

        assertThatThrownBy(() -> factory().getSslContext(CPA, OWN, PARTNER))
            .isInstanceOfSatisfying(EbmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("MTLS_TRUST_ERROR"));
    }

    @Test
    void getSslContext_withoutKeystore_buildsTrustOnlyContextAndSkipsOwnCertLookup() {
        props.setKeystorePath(null);

        assertThat(factory().getSslContext(CPA, OWN, PARTNER)).isNotNull();
        verify(cpa, never()).getPartnerCertificates(anyString(), anyString());
    }
}
