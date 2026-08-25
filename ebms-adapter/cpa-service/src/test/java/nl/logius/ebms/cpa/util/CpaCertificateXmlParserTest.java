package nl.logius.ebms.cpa.util;

import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CpaPartyXmlParser#parseCertificates(String, String)}.
 * Uses BouncyCastle (test-only) to build a real self-signed X.509 certificate
 * so that CertificateFactory.generateCertificate() succeeds inside the parser.
 */
class CpaCertificateXmlParserTest {

    private final CpaPartyXmlParser parser = new CpaPartyXmlParser();

    private static final String CPA_ID = "urn:test:cpa:cert-sync-001";
    private static final String PARTY_A = "00000000000000000001";
    private static final String PARTY_B = "00000000000000000002";

    private static String certABase64;
    private static String certBBase64;
    private static Date certANotBefore;
    private static Date certANotAfter;

    @BeforeAll
    static void generateTestCerts() throws Exception {
        X509Certificate ca = selfSigned("CN=TEST_a");
        X509Certificate cb = selfSigned("CN=TEST_b");
        certABase64 = Base64.getEncoder().encodeToString(ca.getEncoded());
        certBBase64 = Base64.getEncoder().encodeToString(cb.getEncoded());
        certANotBefore = ca.getNotBefore();
        certANotAfter  = ca.getNotAfter();
    }

    private static X509Certificate selfSigned(String cn) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        Date from = new Date();
        Date to = new Date(from.getTime() + 365L * 24 * 60 * 60 * 1000);
        BigInteger sn = new BigInteger(64, new SecureRandom());
        X500Name owner = new X500Name(cn);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            owner, sn, from, to, owner, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private String cpaXml(String certId, String base64Body) {
        return "<CollaborationProtocolAgreement>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate" + (certId != null ? " certId='" + certId + "'" : "") + ">"
            + "    <ds:KeyInfo xmlns:ds='http://www.w3.org/2000/09/xmldsig#'>"
            + "      <ds:X509Data>"
            + "        <ds:X509Certificate>" + base64Body + "</ds:X509Certificate>"
            + "      </ds:X509Data>"
            + "    </ds:KeyInfo>"
            + "  </Certificate>"
            + "</PartyInfo>"
            + "</CollaborationProtocolAgreement>";
    }

    // ── Happy path ───────────────────────────────────────────────────────

    @Test
    void parseCertificates_singleCertWithCertId_buildsEntityWithAllFields() {
        String xml = cpaXml("signing-cert-1", certABase64);

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);

        assertThat(certs).hasSize(1);
        PartnerCertificateEntity c = certs.get(0);
        assertThat(c.getCpaId()).isEqualTo(CPA_ID);
        assertThat(c.getPartyId()).isEqualTo(PARTY_A);
        assertThat(c.getCertificateAlias()).isEqualTo("signing-cert-1");
        assertThat(c.getCertificatePem()).contains("-----BEGIN CERTIFICATE-----");
        assertThat(c.getCertificatePem()).contains("-----END CERTIFICATE-----");
        assertThat(c.getCertificateUsage()).isEqualTo("SIGNING");
        assertThat(c.getValidFrom()).isEqualTo(certANotBefore.toInstant());
        assertThat(c.getValidUntil()).isEqualTo(certANotAfter.toInstant());
    }

    @Test
    void parseCertificates_certIdWithEncrypt_usageIsEncryption() {
        String xml = cpaXml("encryption-key-2", certABase64);
        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateUsage()).isEqualTo("ENCRYPTION");
    }

    @Test
    void parseCertificates_certIdWithoutSignOrEncrypt_usageIsNull() {
        String xml = cpaXml("misc-cert", certABase64);
        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateUsage()).isNull();
    }

    @Test
    void parseCertificates_missingCertIdAttribute_generatesFallbackAlias() {
        String xml = cpaXml(null, certABase64);
        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateAlias()).isEqualTo(PARTY_A + "-cert-0");
    }

    @Test
    void parseCertificates_blankCertIdAttribute_generatesFallbackAlias() {
        String xml = cpaXml("   ", certABase64);
        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateAlias()).isEqualTo(PARTY_A + "-cert-0");
    }

    @Test
    void parseCertificates_base64WithWhitespaceAndNewlines_isStrippedBeforeParsing() {
        // Insert newlines/spaces mid-base64; parser must strip \s+
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < certABase64.length(); i += 40) {
            wrapped.append(certABase64, i, Math.min(i + 40, certABase64.length())).append("\n    ");
        }
        String xml = cpaXml("signing-x", wrapped.toString());
        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateUsage()).isEqualTo("SIGNING");
    }

    @Test
    void parseCertificates_multipleCertsUnderOneParty_fallbackIndicesIncrement() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate><X509Certificate>" + certABase64 + "</X509Certificate></Certificate>"
            + "  <Certificate><X509Certificate>" + certBBase64 + "</X509Certificate></Certificate>"
            + "</PartyInfo>"
            + "</Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);

        assertThat(certs).hasSize(2);
        assertThat(certs).extracting(PartnerCertificateEntity::getCertificateAlias)
            .containsExactly(PARTY_A + "-cert-0", PARTY_A + "-cert-1");
    }

    @Test
    void parseCertificates_multipleParties_bothIncluded() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate certId='sign-a'><X509Certificate>" + certABase64 + "</X509Certificate></Certificate>"
            + "</PartyInfo>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_B + "</PartyId>"
            + "  <Certificate certId='sign-b'><X509Certificate>" + certBBase64 + "</X509Certificate></Certificate>"
            + "</PartyInfo>"
            + "</Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);

        assertThat(certs).hasSize(2);
        assertThat(certs).extracting(PartnerCertificateEntity::getPartyId)
            .containsExactlyInAnyOrder(PARTY_A, PARTY_B);
        assertThat(certs).extracting(PartnerCertificateEntity::getCertificateAlias)
            .containsExactlyInAnyOrder("sign-a", "sign-b");
    }

    @Test
    void parseCertificates_namespacePrefixed_extractsSame() {
        String xml = "<tp:Root xmlns:tp='http://x' xmlns:ds='http://www.w3.org/2000/09/xmldsig#'>"
            + "<tp:PartyInfo>"
            + "  <tp:PartyId type='urn:oin'>" + PARTY_A + "</tp:PartyId>"
            + "  <tp:Certificate certId='signing-1'>"
            + "    <ds:X509Certificate>" + certABase64 + "</ds:X509Certificate>"
            + "  </tp:Certificate>"
            + "</tp:PartyInfo>"
            + "</tp:Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateAlias()).isEqualTo("signing-1");
    }

    // ── Skip / fail-safe rules ───────────────────────────────────────────

    @Test
    void parseCertificates_certificateWithoutX509_isSkipped() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate certId='empty-cert'/>"
            + "</PartyInfo></Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).isEmpty();
    }

    @Test
    void parseCertificates_blankX509Content_isSkipped() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate certId='empty-body'><X509Certificate>   </X509Certificate></Certificate>"
            + "</PartyInfo></Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).isEmpty();
    }

    @Test
    void parseCertificates_garbageBase64_isSkippedNotThrown() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + PARTY_A + "</PartyId>"
            + "  <Certificate certId='broken'><X509Certificate>not-real-base64-!!!</X509Certificate></Certificate>"
            + "  <Certificate certId='signing-ok'><X509Certificate>" + certABase64 + "</X509Certificate></Certificate>"
            + "</PartyInfo></Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        // Broken one skipped; the good one still parsed.
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getCertificateAlias()).isEqualTo("signing-ok");
    }

    @Test
    void parseCertificates_partyInfoWithoutPartyId_isSkipped() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <Certificate certId='c1'><X509Certificate>" + certABase64 + "</X509Certificate></Certificate>"
            + "</PartyInfo></Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).isEmpty();
    }

    @Test
    void parseCertificates_nullXml_returnsEmptyList() {
        assertThat(parser.parseCertificates(null, CPA_ID)).isEmpty();
    }

    @Test
    void parseCertificates_blankXml_returnsEmptyList() {
        assertThat(parser.parseCertificates("   ", CPA_ID)).isEmpty();
    }

    @Test
    void parseCertificates_malformedXml_returnsEmptyListNoThrow() {
        assertThat(parser.parseCertificates("<Root><Unclosed>", CPA_ID)).isEmpty();
    }

    @Test
    void parseCertificates_xmlWithDoctype_isSafelyRejected() {
        String xml = "<?xml version='1.0'?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
            + "<Root><PartyInfo><PartyId type='X'>" + PARTY_A + "</PartyId>"
            + "<Certificate certId='c1'><X509Certificate>" + certABase64
            + "</X509Certificate></Certificate></PartyInfo></Root>";

        List<PartnerCertificateEntity> certs = parser.parseCertificates(xml, CPA_ID);
        assertThat(certs).isEmpty();
    }
}
