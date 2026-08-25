package nl.logius.ebms.cpa.util;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import nl.logius.ebms.common.util.OinValidator;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Extraheert partij-informatie ({@code <PartyInfo>}/{@code <PartyId>}) uit een CPA-document
 * conform OASIS ebXML CPPA v2.0, ongeacht het gebruikte namespace-prefix (bijv. {@code tp:}).
 *
 * <p>Gebruikt door {@link nl.logius.ebms.cpa.service.CpaService} om de {@code cpa_party}
 * tabel te synchroniseren met de daadwerkelijke inhoud van {@code cpaXml} bij het aanmaken of
 * overschrijven van een CPA.
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
}
