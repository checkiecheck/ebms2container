package nl.logius.ebms.cpa.util;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import nl.logius.ebms.common.util.OinValidator;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Extraheert partij-informatie ({@code <PartyInfo>}/{@code <PartyId>}) en ingesloten
 * partnercertificaten ({@code <Certificate>}/{@code <X509Certificate>}) uit een CPA-document
 * conform OASIS ebXML CPPA v2.0, ongeacht het gebruikte namespace-prefix (bijv. {@code tp:}).
 *
 * <p>Gebruikt door {@link nl.logius.ebms.cpa.service.CpaService} om de {@code cpa_party} en
 * {@code partner_certificate} tabellen te synchroniseren met de daadwerkelijke inhoud van
 * {@code cpaXml} bij het aanmaken of overschrijven van een CPA.
 */
@Component
@Slf4j
public class CpaPartyXmlParser {

    /**
     * Parseert alle {@code <PartyInfo>}-elementen uit de CPA XML naar {@link PartyInfoDto}'s.
     * Een {@code <PartyInfo>} zonder {@code <PartyId>} (of met een lege waarde) wordt overgeslagen.
     * Bij een parsefout wordt een lege lijst teruggegeven (fail-safe: de CPA zelf blijft geldig,
     * alleen de partij-synchronisatie wordt overgeslagen).
     *
     * @param cpaXml het volledige CPA-document als string
     * @return lijst van geëxtraheerde partijen (mogelijk leeg)
     */
    public List<PartyInfoDto> parseParties(String cpaXml) {
        if (cpaXml == null || cpaXml.isBlank()) {
            return List.of();
        }
        try {
            Document doc = parseDocument(cpaXml);
            NodeList partyInfoNodes = doc.getElementsByTagNameNS("*", "PartyInfo");

            List<PartyInfoDto> parties = new ArrayList<>();
            for (int i = 0; i < partyInfoNodes.getLength(); i++) {
                PartyInfoDto party = parsePartyInfo((Element) partyInfoNodes.item(i));
                if (party != null) {
                    parties.add(party);
                }
            }
            return parties;
        } catch (Exception e) {
            log.warn("Kon partijen niet uit CPA XML parsen (CPA blijft geldig, partijen worden "
                + "niet gesynchroniseerd): {}", e.getMessage());
            return List.of();
        }
    }

    private Document parseDocument(String cpaXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // XXE-preventie: geen externe entities/DTD's laten oplossen bij het parsen van CPA XML.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(cpaXml.getBytes(StandardCharsets.UTF_8)));
    }

    private PartyInfoDto parsePartyInfo(Element partyInfoEl) {
        Element partyIdEl = firstDescendant(partyInfoEl, "PartyId");
        if (partyIdEl == null) {
            return null;
        }
        String partyId = partyIdEl.getTextContent().trim();
        if (partyId.isEmpty()) {
            return null;
        }

        String partyIdType = blankToNull(partyIdEl.getAttribute("type"));
        String oin = OinValidator.isValid(partyId) ? partyId : null;

        String role = null;
        String service = null;
        Element collaborationRole = firstDescendant(partyInfoEl, "CollaborationRole");
        if (collaborationRole != null) {
            Element roleEl = firstDescendant(collaborationRole, "Role");
            if (roleEl != null) {
                role = blankToNull(roleEl.getAttribute("name"));
            }
            Element serviceBinding = firstDescendant(collaborationRole, "ServiceBinding");
            Element serviceEl = firstDescendant(serviceBinding != null ? serviceBinding : collaborationRole, "Service");
            if (serviceEl != null) {
                service = blankToNull(serviceEl.getTextContent().trim());
            }
        }

        return PartyInfoDto.builder()
            .partyId(partyId)
            .partyIdType(partyIdType)
            .oin(oin)
            .oinValidated(oin != null)
            .role(role)
            .service(service)
            .build();
    }

    /** Eerste directe/geneste child-element met de gegeven lokale naam, ongeacht namespace. */
    private Element firstDescendant(Node parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = ((Element) parent).getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // ── Certificaat-extractie ────────────────────────────────────────────

    /**
     * Extraheert alle per-partij ingesloten certificaten ({@code <Certificate>} met een geneste
     * {@code <X509Certificate>}) uit de CPA XML. Retourneert transiente (niet-opgeslagen)
     * {@link PartnerCertificateEntity}'s; {@code validFrom}/{@code validUntil} worden uit het
     * daadwerkelijke X.509-certificaat gehaald (niet uit de XML zelf, die geen geldigheidsdata
     * bevat). Fail-safe: bij een parse- of certificaatfout wordt die entry overgeslagen (de CPA
     * blijft geldig, alleen certificaat-synchronisatie voor dat element wordt overgeslagen).
     *
     * @param cpaXml het volledige CPA-document als string
     * @param cpaId  de CPA-identifier (niet in de XML zelf op certificaatniveau aanwezig)
     * @return lijst van geëxtraheerde certificaten (mogelijk leeg)
     */
    public List<PartnerCertificateEntity> parseCertificates(String cpaXml, String cpaId) {
        if (cpaXml == null || cpaXml.isBlank()) {
            return List.of();
        }
        try {
            Document doc = parseDocument(cpaXml);
            NodeList partyInfoNodes = doc.getElementsByTagNameNS("*", "PartyInfo");

            List<PartnerCertificateEntity> certificates = new ArrayList<>();
            for (int i = 0; i < partyInfoNodes.getLength(); i++) {
                Element partyInfoEl = (Element) partyInfoNodes.item(i);
                Element partyIdEl = firstDescendant(partyInfoEl, "PartyId");
                if (partyIdEl == null || partyIdEl.getTextContent().isBlank()) {
                    continue;
                }
                String partyId = partyIdEl.getTextContent().trim();

                NodeList certNodes = partyInfoEl.getElementsByTagNameNS("*", "Certificate");
                int index = 0;
                for (int j = 0; j < certNodes.getLength(); j++) {
                    PartnerCertificateEntity cert =
                        parseCertificate((Element) certNodes.item(j), cpaId, partyId, index);
                    if (cert != null) {
                        certificates.add(cert);
                        index++;
                    }
                }
            }
            return certificates;
        } catch (Exception e) {
            log.warn("Kon certificaten niet uit CPA XML parsen (CPA blijft geldig, certificaten "
                + "worden niet gesynchroniseerd): {}", e.getMessage());
            return List.of();
        }
    }

    private PartnerCertificateEntity parseCertificate(Element certEl, String cpaId, String partyId, int index) {
        Element x509El = firstDescendant(certEl, "X509Certificate");
        if (x509El == null || x509El.getTextContent().isBlank()) {
            return null;
        }
        String base64 = x509El.getTextContent().replaceAll("\\s+", "");
        String pem = toPem(base64);

        X509Certificate x509;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            x509 = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Ongeldig X.509-certificaat in CPA XML voor partij {}: {}", partyId, e.getMessage());
            return null;
        }

        String alias = blankToNull(certEl.getAttribute("certId"));
        if (alias == null) {
            alias = partyId + "-cert-" + index;
        }

        return PartnerCertificateEntity.builder()
            .cpaId(cpaId)
            .partyId(partyId)
            .certificateAlias(alias)
            .certificatePem(pem)
            .validFrom(x509.getNotBefore().toInstant())
            .validUntil(x509.getNotAfter().toInstant())
            .certificateUsage(guessUsage(alias))
            .build();
    }

    /** Heuristiek op basis van de certId/alias-naam (bijv. "signing-cert-1", "encryption-cert-1"). */
    private String guessUsage(String alias) {
        String lower = alias.toLowerCase();
        if (lower.contains("sign")) {
            return "SIGNING";
        }
        if (lower.contains("encrypt")) {
            return "ENCRYPTION";
        }
        return null;
    }

    /** Formatteert ruwe base64 DER-data (zoals in ds:X509Certificate) naar PEM. */
    private String toPem(String base64) {
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }
}
