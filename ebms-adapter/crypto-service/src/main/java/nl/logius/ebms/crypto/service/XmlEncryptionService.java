package nl.logius.ebms.crypto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.crypto.entity.CryptoAuditLogEntity;
import nl.logius.ebms.crypto.repository.CryptoAuditLogRepository;
import org.apache.xml.security.Init;
import org.apache.xml.security.encryption.EncryptedData;
import org.apache.xml.security.encryption.EncryptedKey;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.keys.KeyInfo;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
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
 * XML-Encryptie en -decryptie conform W3C XML Encryption Syntax and Processing 1.1:
 * <ul>
 *   <li>Data-encryptie: AES-256-GCM ({@code http://www.w3.org/2009/xmlenc11#aes256-gcm})</li>
 *   <li>Sleutelinkapseling: RSA-OAEP ({@code http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p})</li>
 * </ul>
 *
 * <p>Vereist voor Digikoppeling-profielen {@code osb-be-e} en {@code osb-rm-e}.
 *
 * <p>Verwerking:
 * <ol>
 *   <li>Genereer willekeurige AES-256 sessiesleutel</li>
 *   <li>Versleutel de XML-inhoud met de sessiesleutel (AES-256-GCM)</li>
 *   <li>Versleutel de sessiesleutel met de publieke RSA-sleutel van de ontvanger (RSA-OAEP)</li>
 *   <li>Embed de EncryptedKey in de KeyInfo van EncryptedData</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class XmlEncryptionService {

    private final KeyStoreService          keyStoreService;
    private final CryptoAuditLogRepository auditLogRepository;

    static {
        Init.init();
    }

    // ── Encryptie ─────────────────────────────────────────────────────────

    /**
     * Versleutelt het XML-document met AES-256-GCM voor de ontvanger geïdentificeerd door
     * {@code recipientKeyAlias}. De AES-sessiesleutel wordt verpakt met RSA-OAEP.
     *
     * @param xmlContent        te versleutelen XML-document (UTF-8)
     * @param recipientKeyAlias alias van het ontvanger-certificaat in de KeyStore
     * @param messageId         bericht-ID voor audit (mag null zijn)
     * @return versleuteld XML-document (UTF-8 string) met embedded EncryptedKey
     * @throws XmlSecurityException bij encryptiefouten
     */
    public String encrypt(String xmlContent, String recipientKeyAlias, String messageId) {
        long startMs = System.currentTimeMillis();
        try {
            Document doc = parseXml(xmlContent);

            // Certificaat van ontvanger (publieke sleutel voor RSA-OAEP sleutelinkapseling)
            X509Certificate recipientCert = keyStoreService.getCertificate(recipientKeyAlias);
            PublicKey        recipientKey  = recipientCert.getPublicKey();

            // 1. Genereer willekeurige AES-256 sessiesleutel
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            SecretKey sessionKey = keyGen.generateKey();

            // 2. Versleutel de sessiesleutel met RSA-OAEP
            XMLCipher keyCipher = XMLCipher.getInstance(XMLCipher.RSA_OAEP);
            keyCipher.init(XMLCipher.WRAP_MODE, recipientKey);
            EncryptedKey encryptedKey = keyCipher.encryptKey(doc, sessionKey);

            // 3. Initialiseer AES-256-GCM cipher voor data-encryptie
            XMLCipher xmlCipher = XMLCipher.getInstance(XMLCipher.AES_256_GCM);
            xmlCipher.init(XMLCipher.ENCRYPT_MODE, sessionKey);

            // 4. Voeg EncryptedKey toe aan de KeyInfo van EncryptedData
            EncryptedData encryptedData = xmlCipher.getEncryptedData();
            KeyInfo keyInfo = new KeyInfo(doc);
            keyInfo.add(encryptedKey);
            encryptedData.setKeyInfo(keyInfo);

            // 5. Versleutel het root-element (content=false vervangt het gehele element)
            Element rootElement = doc.getDocumentElement();
            xmlCipher.doFinal(doc, rootElement, false);

            String encryptedXml = serializeDocument(doc);

            persistAudit("XML_ENCRYPT", recipientKeyAlias, messageId, "SUCCESS",
                null, null, (int)(System.currentTimeMillis() - startMs));
            log.info("[XML-ENC] Versleuteld: recipientAlias={} messageId={}", recipientKeyAlias, messageId);

            return encryptedXml;

        } catch (XmlSecurityException e) {
            persistAudit("XML_ENCRYPT", recipientKeyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(), (int)(System.currentTimeMillis() - startMs));
            throw e;
        } catch (Exception e) {
            persistAudit("XML_ENCRYPT", recipientKeyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(), (int)(System.currentTimeMillis() - startMs));
            throw new XmlSecurityException("XML-encryptie mislukt: " + e.getMessage(), e);
        }
    }

    // ── Decryptie ─────────────────────────────────────────────────────────

    /**
     * Ontsleutelt een XML-document dat versleuteld is conform XML-Enc 1.1.
     * De EncryptedKey in het document wordt ontsleuteld met de private RSA-sleutel
     * geïdentificeerd door {@code keyAlias}.
     *
     * @param encryptedXml versleuteld XML-document (UTF-8)
     * @param keyAlias     alias van onze private sleutel in de KeyStore
     * @param messageId    bericht-ID voor audit (mag null zijn)
     * @return ontsleuteld XML-document (UTF-8 string)
     * @throws XmlSecurityException bij decryptiefouten
     */
    public String decrypt(String encryptedXml, String keyAlias, String messageId) {
        long startMs = System.currentTimeMillis();
        try {
            Document doc = parseXml(encryptedXml);

            // Onze private sleutel voor RSA-OAEP ontsleuteling van de sessiesleutel
            PrivateKey privateKey = keyStoreService.getPrivateKey(keyAlias);

            // Zoek het EncryptedData-element
            NodeList nl = doc.getElementsByTagNameNS(
                "http://www.w3.org/2001/04/xmlenc#", "EncryptedData");
            if (nl.getLength() == 0) {
                throw new XmlSecurityException("Geen EncryptedData-element gevonden in document");
            }
            Element encryptedDataElement = (Element) nl.item(0);

            // Initialiseer cipher voor decryptie; KEK ontsleutelt de embedded EncryptedKey
            XMLCipher xmlCipher = XMLCipher.getInstance();
            xmlCipher.init(XMLCipher.DECRYPT_MODE, null);
            xmlCipher.setKEK(privateKey);

            // Ontsleutel
            xmlCipher.doFinal(doc, encryptedDataElement);

            String decryptedXml = serializeDocument(doc);

            persistAudit("XML_DECRYPT", keyAlias, messageId, "SUCCESS",
                null, null, (int)(System.currentTimeMillis() - startMs));
            log.info("[XML-DEC] Ontsleuteld: keyAlias={} messageId={}", keyAlias, messageId);

            return decryptedXml;

        } catch (XmlSecurityException e) {
            persistAudit("XML_DECRYPT", keyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(), (int)(System.currentTimeMillis() - startMs));
            throw e;
        } catch (Exception e) {
            persistAudit("XML_DECRYPT", keyAlias, messageId, "FAILURE",
                "SecurityFailure", e.getMessage(), (int)(System.currentTimeMillis() - startMs));
            throw new XmlSecurityException("XML-decryptie mislukt: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Document parseXml(String xmlContent) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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

    private void persistAudit(String operation, String keyAlias, String messageId,
                                String result, String errorCode, String errorDetail, int durationMs) {
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
