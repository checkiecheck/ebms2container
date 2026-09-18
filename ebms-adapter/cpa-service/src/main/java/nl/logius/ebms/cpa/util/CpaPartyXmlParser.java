package nl.logius.ebms.cpa.util;

import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.InputSource;
import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import nl.logius.ebms.common.util.OinValidator;
import nl.logius.ebms.cpa.entity.CpaDeliveryChannelEntity;
import nl.logius.ebms.cpa.entity.CpaOutboundRouteEntity;
import nl.logius.ebms.cpa.entity.PartnerCertificateEntity;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
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
public String parseCpaId(String cpaXml) {
    if (cpaXml == null || cpaXml.isBlank()) return null;
    try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(cpaXml)));
        Element root = doc.getDocumentElement();
        return getLenientAttribute(root, "cpaId");
    } catch (Exception e) {
        return null;
    }
}

public Instant parseStartDate(String cpaXml) {
    String dateStr = parseDateAttribute(cpaXml, "Start");
    try {
        return dateStr != null ? Instant.parse(dateStr) : null;
    } catch (java.time.format.DateTimeParseException e) {
        log.warn("Could not parse start date from CPA XML: {}", dateStr, e);
        return null;
    }
}

public Instant parseEndDate(String cpaXml) {
    String dateStr = parseDateAttribute(cpaXml, "End");
    try {
        return dateStr != null ? Instant.parse(dateStr) : null;
    } catch (java.time.format.DateTimeParseException e) {
        log.warn("Could not parse end date from CPA XML: {}", dateStr, e);
        return null;
    }
}

private String parseDateAttribute(String cpaXml, String attributeName) {
    if (cpaXml == null || cpaXml.isBlank()) return null;
    try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(cpaXml)));
        Element root = doc.getDocumentElement();
        
        // Look for <tns:Start> or <Start> element values inside root
        org.w3c.dom.NodeList list = root.getElementsByTagNameNS("*", attributeName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
    } catch (Exception e) {
        // Fallback or log
    }
    return null;
}

private static String getLenientAttribute(Element element, String attributeName) {
    if (element == null) return null;
    NamedNodeMap attributes = element.getAttributes();
    if (attributes == null) return null;
    String target = attributeName.toLowerCase();
    for (int i = 0; i < attributes.getLength(); i++) {
        Node attr = attributes.item(i);
        String localName = attr.getLocalName();
        if (localName == null) {
            String nodeName = attr.getNodeName();
            localName = nodeName.contains(":") ? nodeName.substring(nodeName.indexOf(":") + 1) : nodeName;
        }
        if (localName.equalsIgnoreCase(target)) {
            return attr.getNodeValue();
        }
    }
    return null;
}

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

    public List<CpaOutboundRouteEntity> parseOutboundRoutes(String cpaXml, String cpaId) {
        if (cpaXml == null || cpaXml.isBlank()) {
            return List.of();
        }
        try {
            Document document = parseDocument(cpaXml);
            Map<String, ActionBinding> bindingsById = new HashMap<>();
            Map<String, String> channelOwners = new HashMap<>();
            Map<String, SecurityConfig> securityByChannel = new HashMap<>();
            List<ActionBinding> sendBindings = new ArrayList<>();

            NodeList partyNodes = document.getElementsByTagNameNS("*", "PartyInfo");
            for (int i = 0; i < partyNodes.getLength(); i++) {
                Element party = (Element) partyNodes.item(i);
                Element partyIdElement = firstDescendant(party, "PartyId");
                if (partyIdElement == null || partyIdElement.getTextContent().isBlank()) {
                    continue;
                }
                String partyId = partyIdElement.getTextContent().trim();

                for (Element channel : descendants(party, "DeliveryChannel")) {
                    String channelId = blankToNull(getLenientAttribute(channel, "channelId"));
                    if (channelId != null) {
                        channelOwners.put(channelId, partyId);
                        String docExchangeId = getLenientAttribute(channel, "docExchangeId");
                        Element docExchange = docExchangeId == null
                            ? null : descendantByAttribute(party, "DocExchange", "docExchangeId", docExchangeId);
                        securityByChannel.put(channelId, parseSecurityConfig(docExchange));
                    }
                }

                for (Element collaborationRole : descendants(party, "CollaborationRole")) {
                    Element roleElement = directChild(collaborationRole, "Role");
                    String role = roleElement == null
                        ? null : blankToNull(getLenientAttribute(roleElement, "name"));
                    for (Element serviceBinding : directChildren(collaborationRole, "ServiceBinding")) {
                        Element serviceElement = directChild(serviceBinding, "Service");
                        String service = serviceElement == null
                            ? null : blankToNull(serviceElement.getTextContent().trim());
                        String serviceType = serviceElement == null
                            ? null : blankToNull(getLenientAttribute(serviceElement, "type"));
                        collectActionBindings(serviceBinding, "CanSend", true, partyId, role,
                            service, serviceType, bindingsById, sendBindings);
                        collectActionBindings(serviceBinding, "CanReceive", false, partyId, role,
                            service, serviceType, bindingsById, sendBindings);
                    }
                }
            }

            List<CpaOutboundRouteEntity> routes = new ArrayList<>();
            for (ActionBinding sender : sendBindings) {
                ActionBinding receiver = bindingsById.get(sender.otherBindingId());
                if (!sender.completeSender() || receiver == null || receiver.send()
                        || !receiver.completeReceiver()) {
                    log.warn("Onvolledige outbound action-binding in CPA {}: binding={} other={}",
                        cpaId, sender.id(), sender.otherBindingId());
                    continue;
                }
                if (!sender.service().equals(receiver.service())
                    || !java.util.Objects.equals(sender.serviceType(), receiver.serviceType())
                        || !sender.action().equals(receiver.action())) {
                    log.warn("Inconsistente gekoppelde action-bindings in CPA {}: {} -> {}",
                        cpaId, sender.id(), receiver.id());
                    continue;
                }
                for (String channelId : receiver.channelIds()) {
                    String channelPartyId = channelOwners.get(channelId);
                    if (channelPartyId == null) {
                        log.warn("Onopgeloste ChannelId in outbound route van CPA {}: binding={} channel={}",
                            cpaId, sender.id(), channelId);
                        continue;
                    }
                    routes.add(CpaOutboundRouteEntity.builder()
                        .cpaId(cpaId)
                        .fromPartyId(sender.partyId())
                        .toPartyId(receiver.partyId())
                        .service(sender.service())
                        .serviceType(sender.serviceType())
                        .action(sender.action())
                        .actionBindingId(sender.id())
                        .fromRole(sender.role())
                        .toRole(receiver.role())
                        .signatureRequired(securityByChannel.getOrDefault(
                            channelId, SecurityConfig.NONE).signatureRequired())
                        .hashFunction(securityByChannel.getOrDefault(
                            channelId, SecurityConfig.NONE).hashFunction())
                        .signatureAlgorithm(securityByChannel.getOrDefault(
                            channelId, SecurityConfig.NONE).signatureAlgorithm())
                        .channelPartyId(channelPartyId)
                        .channelId(channelId)
                        .build());
                }
            }
            return routes;
        } catch (Exception e) {
            log.warn("Kon outbound routes niet uit CPA XML parsen: {}", e.getMessage());
            return List.of();
        }
    }

    private void collectActionBindings(Element serviceBinding, String direction, boolean send,
            String partyId, String role, String service, String serviceType,
            Map<String, ActionBinding> bindingsById, List<ActionBinding> sendBindings) {
        for (Element capability : directChildren(serviceBinding, direction)) {
            Element bindingElement = directChild(capability, "ThisPartyActionBinding");
            if (bindingElement == null) {
                continue;
            }
            Element otherElement = directChild(capability, "OtherPartyActionBinding");
            String id = blankToNull(getLenientAttribute(bindingElement, "id"));
            String action = blankToNull(getLenientAttribute(bindingElement, "action"));
            String otherId = otherElement == null
                ? null : blankToNull(otherElement.getTextContent().trim());
            List<String> channelIds = directChildren(bindingElement, "ChannelId").stream()
                .map(Element::getTextContent)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
            ActionBinding binding = new ActionBinding(
                id, partyId, role, service, serviceType, action, otherId, channelIds, send);
            if (id != null) {
                bindingsById.put(id, binding);
            }
            if (send) {
                sendBindings.add(binding);
            }
        }
    }

    private List<Element> descendants(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private List<Element> directChildren(Element parent, String localName) {
        List<Element> elements = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(localName(element))) {
                elements.add(element);
            }
        }
        return elements;
    }

    private String localName(Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = element.getNodeName();
        int separator = nodeName.indexOf(':');
        return separator >= 0 ? nodeName.substring(separator + 1) : nodeName;
    }

    private Element directChild(Element parent, String localName) {
        List<Element> children = directChildren(parent, localName);
        return children.isEmpty() ? null : children.get(0);
    }

    private Element descendantByAttribute(Element parent, String elementName,
            String attributeName, String expectedValue) {
        for (Element element : descendants(parent, elementName)) {
            if (expectedValue.equals(getLenientAttribute(element, attributeName))) {
                return element;
            }
        }
        return null;
    }

    private SecurityConfig parseSecurityConfig(Element docExchange) {
        if (docExchange == null) return SecurityConfig.NONE;
        Element nonRepudiation = firstDescendant(docExchange, "SenderNonRepudiation");
        if (nonRepudiation == null) {
            nonRepudiation = firstDescendant(docExchange, "ReceiverNonRepudiation");
        }
        if (nonRepudiation == null) return SecurityConfig.NONE;
        return new SecurityConfig(true,
            normalizeHashFunction(textOf(firstDescendant(nonRepudiation, "HashFunction"))),
            normalizeSignatureAlgorithm(
                textOf(firstDescendant(nonRepudiation, "SignatureAlgorithm"))));
    }

    private String normalizeHashFunction(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "sha1", "sha-1", "http://www.w3.org/2000/09/xmldsig#sha1" ->
                "http://www.w3.org/2000/09/xmldsig#sha1";
            case "sha256", "sha-256", "http://www.w3.org/2001/04/xmlenc#sha256" ->
                "http://www.w3.org/2001/04/xmlenc#sha256";
            default -> value.trim();
        };
    }

    private String normalizeSignatureAlgorithm(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "rsa-sha1", "rsa_sha1", "http://www.w3.org/2000/09/xmldsig#rsa-sha1" ->
                "http://www.w3.org/2000/09/xmldsig#rsa-sha1";
            case "rsa-sha256", "rsa_sha256", "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256" ->
                "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
            case "ecdsa-sha256", "ecdsa_sha256", "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256" ->
                "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";
            default -> value.trim();
        };
    }

    private record ActionBinding(String id, String partyId, String role, String service,
                                 String serviceType, String action, String otherBindingId,
                                 List<String> channelIds, boolean send) {
        private boolean completeSender() {
            return id != null && partyId != null && role != null && service != null
                && action != null && otherBindingId != null && !channelIds.isEmpty();
        }

        private boolean completeReceiver() {
            return id != null && partyId != null && role != null && service != null
                && action != null && !channelIds.isEmpty();
        }
    }

    private record SecurityConfig(boolean signatureRequired, String hashFunction,
                                  String signatureAlgorithm) {
        private static final SecurityConfig NONE = new SecurityConfig(false, null, null);
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

    // ── Afleverkanaal-extractie ──────────────────────────────────────────

    /**
     * Extraheert alle {@code <DeliveryChannel>}-elementen per partij uit de CPA XML, met de
     * gekoppelde {@code <Transport>} (endpoint-URL) en {@code <DocExchange>} (Reliable
     * Messaging-parameters + sign/encrypt-detectie voor het Digikoppeling-profiel) op basis van
     * de {@code channelId}/{@code transportId}/{@code docExchangeId} ID-referenties binnen
     * dezelfde {@code <PartyInfo>}. Retourneert transiente (niet-opgeslagen)
     * {@link CpaDeliveryChannelEntity}'s. Fail-safe: bij een parsefout wordt een lege lijst
     * teruggegeven (de CPA blijft geldig, alleen kanaal-synchronisatie wordt overgeslagen).
     *
     * <p><b>Let op:</b> {@code dkProfile} staat niet als los attribuut in de generieke ebXML
     * CPPA-schema en wordt daarom afgeleid (heuristiek): aanwezigheid van
     * {@code <ReliableMessaging>} bepaalt "rm" vs "be", aanwezigheid van
     * {@code Sender/ReceiverNonRepudiation} bepaalt de "-s"-suffix en aanwezigheid van
     * {@code Sender/ReceiverDigitalEnvelope} de "-e"-suffix. Zonder treffers: {@code osb-be}.
     *
     * @param cpaXml het volledige CPA-document als string
     * @param cpaId  de CPA-identifier (niet op kanaalniveau aanwezig in de XML zelf)
     * @return lijst van geëxtraheerde afleverkanalen (mogelijk leeg)
     */
    public List<CpaDeliveryChannelEntity> parseDeliveryChannels(String cpaXml, String cpaId) {
        if (cpaXml == null || cpaXml.isBlank()) {
            return List.of();
        }
        try {
            Document doc = parseDocument(cpaXml);
            NodeList partyInfoNodes = doc.getElementsByTagNameNS("*", "PartyInfo");

            List<CpaDeliveryChannelEntity> channels = new ArrayList<>();
            for (int i = 0; i < partyInfoNodes.getLength(); i++) {
                Element partyInfoEl = (Element) partyInfoNodes.item(i);
                Element partyIdEl = firstDescendant(partyInfoEl, "PartyId");
                if (partyIdEl == null || partyIdEl.getTextContent().isBlank()) {
                    continue;
                }
                String partyId = partyIdEl.getTextContent().trim();

                Map<String, Element> transportsById = indexById(partyInfoEl, "Transport", "transportId");
                Map<String, Element> docExchangesById = indexById(partyInfoEl, "DocExchange", "docExchangeId");

                NodeList channelNodes = partyInfoEl.getElementsByTagNameNS("*", "DeliveryChannel");
                for (int j = 0; j < channelNodes.getLength(); j++) {
                    CpaDeliveryChannelEntity channel = parseDeliveryChannel(
                        (Element) channelNodes.item(j), cpaId, partyId, transportsById, docExchangesById);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
            return channels;
        } catch (Exception e) {
            log.warn("Kon afleverkanalen niet uit CPA XML parsen (CPA blijft geldig, kanalen "
                + "worden niet gesynchroniseerd): {}", e.getMessage());
            return List.of();
        }
    }

    /** Indexeert directe elementen met de gegeven lokale naam op hun ID-attribuut (bv. transportId). */
    private Map<String, Element> indexById(Element parent, String localName, String idAttribute) {
        Map<String, Element> byId = new HashMap<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String id = getLenientAttribute(el, idAttribute);
            if (id != null && !id.isBlank()) {
                byId.put(id, el);
            }
        }
        return byId;
    }

    private CpaDeliveryChannelEntity parseDeliveryChannel(Element channelEl, String cpaId, String partyId,
            Map<String, Element> transportsById, Map<String, Element> docExchangesById) {
        String channelId = getLenientAttribute(channelEl, "channelId");
        if (channelId == null || channelId.isBlank()) {
            return null;
        }

        String endpointUrl = null;
        String transportId = getLenientAttribute(channelEl, "transportId");
        Element transportEl = transportId != null ? transportsById.get(transportId) : null;
        if (transportEl != null) {
            Element endpointEl = firstDescendant(transportEl, "Endpoint");
            if (endpointEl != null) {
                endpointUrl = blankToNull(getLenientAttribute(endpointEl, "uri"));
            }
        }

        Integer retryCount = null;
        Integer retryInterval = null;
        Integer persistDuration = null;
        boolean reliableMessaging = false;
        boolean signed = false;
        boolean encrypted = false;
        String syncReplyMode = null;

        String docExchangeId = getLenientAttribute(channelEl, "docExchangeId");
        Element docExchangeEl = docExchangeId != null ? docExchangesById.get(docExchangeId) : null;
        if (docExchangeEl != null) {
            NodeList rmNodes = docExchangeEl.getElementsByTagNameNS("*", "ReliableMessaging");
            if (rmNodes.getLength() > 0) {
                reliableMessaging = true;
                Element rmEl = (Element) rmNodes.item(0);
                retryCount = parseIntSafe(textOf(firstDescendant(rmEl, "Retries")));
                retryInterval = parseDurationSecondsSafe(textOf(firstDescendant(rmEl, "RetryInterval")));
            }
            signed = docExchangeEl.getElementsByTagNameNS("*", "SenderNonRepudiation").getLength() > 0
                || docExchangeEl.getElementsByTagNameNS("*", "ReceiverNonRepudiation").getLength() > 0;
            encrypted = docExchangeEl.getElementsByTagNameNS("*", "SenderDigitalEnvelope").getLength() > 0
                || docExchangeEl.getElementsByTagNameNS("*", "ReceiverDigitalEnvelope").getLength() > 0;
            Element persistEl = firstDescendant(docExchangeEl, "PersistDuration");
            if (persistEl != null) {
                persistDuration = parseDurationSecondsSafe(persistEl.getTextContent());
            }
            // SyncReplyModule (Koppelvlakstandaard ebMS2 v3.3+): "none" is de Digikoppeling-
            // default (async), "mshSignalsOnly" moet bilateraal in de CPA afgesproken zijn.
            NodeList mcNodes = docExchangeEl.getElementsByTagNameNS("*", "MessagingCharacteristics");
            if (mcNodes.getLength() > 0) {
                syncReplyMode = blankToNull(getLenientAttribute((Element) mcNodes.item(0), "syncReplyMode"));
            }
        }

        return CpaDeliveryChannelEntity.builder()
            .cpaId(cpaId)
            .partyId(partyId)
            .channelId(channelId)
            .dkProfile(guessDkProfile(reliableMessaging, signed, encrypted))
            .transportProtocol("HTTP")
            .endpointUrl(endpointUrl)
            .retryCount(retryCount)
            .retryInterval(retryInterval)
            .persistDuration(persistDuration)
            .syncReplyMode(syncReplyMode)
            .build();
    }

    /** Digikoppeling-profielcode conform de RM/Sign/Encrypt-tabel; osb-be als niets is gedetecteerd. */
    private String guessDkProfile(boolean reliableMessaging, boolean signed, boolean encrypted) {
        if (encrypted) {
            return reliableMessaging ? "osb-rm-e" : "osb-be-e";
        }
        if (signed) {
            return reliableMessaging ? "osb-rm-s" : "osb-be-s";
        }
        return reliableMessaging ? "osb-rm" : "osb-be";
    }

    private Integer parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parseert seconden als plein getal, of anders een ISO-8601 duur (bv. PT5M) naar seconden. */
    private Integer parseDurationSecondsSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            // Geen plein getal - probeer ISO-8601 duur-notatie.
        }
        try {
            return (int) java.time.Duration.parse(trimmed).getSeconds();
        } catch (Exception e) {
            return null;
        }
    }

    private String textOf(Element element) {
        return element != null ? element.getTextContent().trim() : null;
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
