package nl.logius.ebms.crypto.service;

import nl.logius.ebms.crypto.repository.CryptoAuditLogRepository;
import org.apache.xml.security.utils.Constants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying the SOAP-Header-aware append-target logic in
 * {@link XmlSigningService#sign(String, String, String)}.
 *
 * BUG: Previously the &lt;ds:Signature&gt; was appended directly under the
 * SOAP root &lt;soapenv:Envelope&gt;, which is a schema violation (Envelope
 * may only contain Header + Body), causing SAAJ/CXF at the receiver to
 * silently strip/reject the signature.
 *
 * FIX: XmlSigningService.sign() should now locate the SOAP Header (by
 * namespace + literal tag fallbacks) and append the Signature there. For
 * non-SOAP XML, it must fall back to the document root (regression check).
 */
class XmlSigningServiceTest {

    private static final String ALIAS = "TEST_signing_key";
    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope";

    private XmlSigningService service;
    private KeyStoreService keyStoreService;
    private CryptoAuditLogRepository auditRepo;
    private PrivateKey privateKey;
    private X509Certificate certificate;

    @BeforeAll
    static void initSantuario() {
        org.apache.xml.security.Init.init();
    }

    @BeforeEach
    void setUp() throws Exception {
        // Generate an ephemeral RSA keypair + self-signed cert (JCA only, no BC required)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();
        certificate = generateSelfSignedCert(kp);

        keyStoreService = mock(KeyStoreService.class);
        when(keyStoreService.getPrivateKey(ALIAS)).thenReturn(privateKey);
        when(keyStoreService.getCertificate(ALIAS)).thenReturn(certificate);

        auditRepo = mock(CryptoAuditLogRepository.class);
        when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new XmlSigningService(keyStoreService, auditRepo);
    }

    // ── SOAP 1.1 with soapenv prefix (namespace lookup path) ──────────────
    @Test
    void sign_shouldAppendSignatureToSoap11Header_whenHeaderPresent() throws Exception {
        String soapXml =
            "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\">" +
            "  <soapenv:Header/>" +
            "  <soapenv:Body><Ping>hi</Ping></soapenv:Body>" +
            "</soapenv:Envelope>";

        String signed = service.sign(soapXml, ALIAS, "msg-soap11");

        Element sigParent = findSignatureParent(signed);
        assertThat(sigParent.getLocalName()).isEqualTo("Header");
        assertThat(sigParent.getNamespaceURI()).isEqualTo(SOAP11_NS);
    }

    // ── SOAP 1.2 with different prefix + namespace lookup ─────────────────
    @Test
    void sign_shouldAppendSignatureToSoap12Header_whenHeaderPresent() throws Exception {
        String soapXml =
            "<env:Envelope xmlns:env=\"" + SOAP12_NS + "\">" +
            "  <env:Header/>" +
            "  <env:Body><Ping>hi</Ping></env:Body>" +
            "</env:Envelope>";

        String signed = service.sign(soapXml, ALIAS, "msg-soap12");

        Element sigParent = findSignatureParent(signed);
        assertThat(sigParent.getLocalName()).isEqualTo("Header");
        assertThat(sigParent.getNamespaceURI()).isEqualTo(SOAP12_NS);
    }

    // ── Literal tag fallback: element carries literal prefix "soapenv:Header"
    // without a proper namespace binding (edge case defensive path).
    @Test
    void sign_shouldAppendSignatureToLiteralSoapenvHeader_whenNoNamespace() throws Exception {
        // Both Envelope and Header carry literal prefix and DO have a namespace
        // to keep XML valid; but the header namespace is NOT one of the known
        // SOAP namespaces, forcing the literal-tag fallback.
        String soapXml =
            "<soapenv:Envelope xmlns:soapenv=\"urn:custom:soap\">" +
            "  <soapenv:Header/>" +
            "  <soapenv:Body><Ping>hi</Ping></soapenv:Body>" +
            "</soapenv:Envelope>";

        String signed = service.sign(soapXml, ALIAS, "msg-literal");

        Element sigParent = findSignatureParent(signed);
        assertThat(sigParent.getLocalName()).isEqualTo("Header");
        // Signature MUST NOT be under Envelope
        assertThat(sigParent.getTagName()).isNotEqualTo("soapenv:Envelope");
    }

    // ── Regression: non-SOAP plain XML falls back to root element ─────────
    @Test
    void sign_shouldFallbackToDocumentRoot_forNonSoapXml() throws Exception {
        String plainXml = "<root><data>hello</data></root>";

        String signed = service.sign(plainXml, ALIAS, "msg-plain");

        Element sigParent = findSignatureParent(signed);
        assertThat(sigParent.getTagName()).isEqualTo("root");
    }

    // ── Extra safety: Signature must NEVER be a direct child of Envelope
    @Test
    void sign_shouldNeverAppendSignatureDirectlyUnderEnvelope() throws Exception {
        String soapXml =
            "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\">" +
            "  <soapenv:Header/>" +
            "  <soapenv:Body><Ping>hi</Ping></soapenv:Body>" +
            "</soapenv:Envelope>";

        String signed = service.sign(soapXml, ALIAS, "msg-anti-regression");

        Document doc = parse(signed);
        NodeList sigs = doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature");
        assertThat(sigs.getLength()).isEqualTo(1);
        Node parent = sigs.item(0).getParentNode();
        assertThat(parent.getLocalName()).isEqualTo("Header");
        assertThat(parent.getLocalName()).isNotEqualTo("Envelope");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Element findSignatureParent(String signedXml) throws Exception {
        Document doc = parse(signedXml);
        NodeList sigs = doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature");
        assertThat(sigs.getLength()).as("Signature element should be present").isEqualTo(1);
        return (Element) sigs.item(0).getParentNode();
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private X509Certificate generateSelfSignedCert(KeyPair kp) throws Exception {
        // Uses Bouncy Castle (already a runtime dep of crypto-service) to build a
        // minimal self-signed X.509 v3 certificate for testing purposes only.
        Date from = new Date();
        Date to = new Date(from.getTime() + 365L * 24 * 60 * 60 * 1000);
        BigInteger sn = new BigInteger(64, new java.security.SecureRandom());
        X500Name owner = new X500Name("CN=TEST_signing_cert");

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            owner, sn, from, to, owner, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
