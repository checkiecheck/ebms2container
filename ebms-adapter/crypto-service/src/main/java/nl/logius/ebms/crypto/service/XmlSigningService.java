package nl.logius.ebms.crypto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.crypto.entity.CryptoAuditLogEntity;
import nl.logius.ebms.crypto.repository.CryptoAuditLogRepository;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

/**
 * XML-DSig ondertekening en verificatie conform:
 * <ul>
 *   <li>W3C XML Signature Syntax and Processing (XML-DSig)</li>
 *   <li>Digikoppeling Beveiligingsstandaarden – Certificaat- en Sleutelbeheer</li>
 * </ul>
 *
 * <p>Ondersteunde algoritmen:
 * <ul>
 *   <li>RSA-SHA256 ({@code http://www.w3.org/2001/04/xmldsig-more#rsa-sha256})</li>
 *   <li>ECDSA-SHA256 ({@code http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256})</li>
 * </ul>
 *
 * <p>Canonicalisatie: Exclusive C14N zonder comments
 * ({@code http://www.w3.org/2001/10/xml-exc-c14n#})
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class XmlSigningService {

    private final KeyStoreService         keyStoreService;
    private final CryptoAuditLogRepository auditLogRepository;

    static {
        // Initialiseer Apache Santuario (eenmalig via static block)
        Init.init();
    }

    // ── Ondertekening ─────────────────────────────────────────────────────

    /**
     * Ondertekent het XML-document met de private sleutel van de gegeven alias.
     * Produceert een enveloped XML-DSig handtekening.
     *
     * @param xmlContent het te ondertekenen XML-document (UTF-8 string)
     * @param keyAlias   alias in de KeyStore
     * @param messageId  bericht-ID voor audit (mag null zijn)
     * @return het ondertekende XML-document als UTF-8 string
     * @throws XmlSecurityException bij een ondertekeningsfout
     */
    public String sign(String xmlContent, String keyAlias, String messageId) {
        long startMs = System.currentTimeMillis();
        try {
            Document doc = parseXml(xmlContent);

            PrivateKey     privateKey = keyStoreService.getPrivateKey(keyAlias);
            X509Certificate cert      = keyStoreService.getCertificate(keyAlias);

            // Bepaal algoritme op basis van sleuteltype
            String sigAlgo = determineSignatureAlgorithm(cert);

            // Maak XMLSignature aan (enveloped)
            XMLSignature signature = new XMLSignature(doc, "", sigAlgo);
            doc.getDocumentElement().appendChild(signature.getElement());

            // Voeg transforms toe: enveloped signature + exclusive C14N
            Transforms transforms = new Transforms(doc);
            transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
            transforms.addTransform(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);

            // Onderteken het root-element (lege URI = geheel document)
            signature.addDocument("", transforms, Constants.ALGO_ID_DIGEST_SHA256);

            // Voeg KeyInfo toe (certificaat voor verificatie)
            signature.addKeyInfo(cert);
            signature.addKeyInfo(cert.getPublicKey());

            // Onderteken
            signature.sign(privateKey);

            String signedXml = serializeDocument(doc);
            persistAudit("XML_SIGN", keyAlias, messageId, "SUCCESS", null, null,
                (int)(System.currentTimeMillis() - startMs));
            log.info("[XML-SIGN] Ondertekend: keyAlias={} messageId={} algo={}",
                keyAlias, messageId, sigAlgo);
            return signedXml;

        } catch (XmlSecurityException e) {
            persistAudit("XML_SIGN", keyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(),
                (int)(System.currentTimeMillis() - startMs));
            throw e;
        } catch (Exception e) {
            persistAudit("XML_SIGN", keyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(),
                (int)(System.currentTimeMillis() - startMs));
            throw new XmlSecurityException("XML-ondertekening mislukt: " + e.getMessage(), e);
        }
    }

    // ── Verificatie ───────────────────────────────────────────────────────

    /**
     * Verifieert de XML-DSig handtekening in het ondertekende XML-document.
     *
     * @param signedXml het ondertekende XML-document (UTF-8 string)
     * @param messageId bericht-ID voor audit (mag null zijn)
     * @return true als de handtekening geldig is
     * @throws XmlSecurityException bij een verwerkingsfout
     */
    public boolean verify(String signedXml, String messageId) {
        long startMs = System.currentTimeMillis();
        try {
            Document doc = parseXml(signedXml);

            // Zoek het Signature-element
            NodeList signatures = doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature");
            if (signatures.getLength() == 0) {
                log.warn("[XML-VERIFY] Geen Signature-element gevonden, messageId={}", messageId);
                return false;
            }

            Element signatureElement = (Element) signatures.item(0);
            XMLSignature signature   = new XMLSignature(signatureElement, "");

            // Haal publieke sleutel op uit KeyInfo in het document
            boolean valid = false;
            org.apache.xml.security.keys.KeyInfo ki = signature.getKeyInfo();
            if (ki != null) {
                X509Certificate cert = ki.getX509Certificate();
                if (cert != null) {
                    valid = signature.checkSignatureValue(cert);
                    log.debug("[XML-VERIFY] Certificaat: subject={}", cert.getSubjectX500Principal());
                } else {
                    PublicKey pk = ki.getPublicKey();
                    if (pk != null) {
                        valid = signature.checkSignatureValue(pk);
                    }
                }
            }

            String result = valid ? "SUCCESS" : "FAILURE";
            persistAudit("XML_VERIFY", null, messageId, result,
                valid ? null : "SignatureInvalid",
                valid ? null : "Handtekening kon niet worden geverifieerd",
                (int)(System.currentTimeMillis() - startMs));
            log.info("[XML-VERIFY] messageId={} valid={}", messageId, valid);
            return valid;

        } catch (Exception e) {
            persistAudit("XML_VERIFY", null, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(),
                (int)(System.currentTimeMillis() - startMs));
            throw new XmlSecurityException("XML-verificatie mislukt: " + e.getMessage(), e);
        }
    }

    // ── C14N canonicalisatie ──────────────────────────────────────────────

    /**
     * Voert Exclusive XML Canonicalization (C14N) uit op het gegeven XML-document.
     * Vereist voor reproduceerbare handtekeningen over netwerkgrenzen.
     *
     * @param xmlContent het XML-document
     * @return gecanonicaliseerde XML als UTF-8 byte-array
     */
    public byte[] canonicalize(String xmlContent) {
        try {
            Document doc = parseXml(xmlContent);
            Canonicalizer c14n = Canonicalizer.getInstance(
                Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            return c14n.canonicalizeSubtree(doc.getDocumentElement());
        } catch (Exception e) {
            throw new XmlSecurityException("C14N-canonicalisatie mislukt: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Document parseXml(String xmlContent) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // XXE-preventie
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
    }

    private String serializeDocument(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toString(StandardCharsets.UTF_8);
    }

    private String determineSignatureAlgorithm(X509Certificate cert) {
        String algorithm = cert.getPublicKey().getAlgorithm();
        return switch (algorithm) {
            case "EC"  -> XMLSignature.ALGO_ID_SIGNATURE_ECDSA_SHA256;
            default    -> XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256;
        };
    }

    private void persistAudit(String operation, String keyAlias, String messageId,
                                String result, String errorCode, String errorDetail,
                                int durationMs) {
        try {
            auditLogRepository.save(CryptoAuditLogEntity.builder()
                .operation(operation)
                .keyAlias(keyAlias)
                .messageId(messageId)
                .result(result)
                .errorCode(errorCode)
                .errorDetail(errorDetail)
                .durationMs(durationMs)
                .build());
        } catch (Exception e) {
            log.warn("Auditlog kon niet worden opgeslagen: {}", e.getMessage());
        }
    }
}
