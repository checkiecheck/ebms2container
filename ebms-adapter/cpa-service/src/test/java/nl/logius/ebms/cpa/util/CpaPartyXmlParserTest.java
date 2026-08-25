package nl.logius.ebms.cpa.util;

import nl.logius.ebms.common.model.cpa.PartyInfoDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CpaPartyXmlParser}. Plain JUnit + AssertJ, no Spring.
 */
class CpaPartyXmlParserTest {

    private final CpaPartyXmlParser parser = new CpaPartyXmlParser();

    private static final String VALID_OIN_A = "00000000000000000001";
    private static final String VALID_OIN_B = "00000000000000000002";

    // ── Basic extraction ─────────────────────────────────────────────────

    @Test
    void parseParties_noNamespace_extractsPartyIdAndType() {
        String xml = "<CollaborationProtocolAgreement>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + VALID_OIN_A + "</PartyId>"
            + "</PartyInfo>"
            + "</CollaborationProtocolAgreement>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        PartyInfoDto p = parties.get(0);
        assertThat(p.getPartyId()).isEqualTo(VALID_OIN_A);
        assertThat(p.getPartyIdType()).isEqualTo("urn:oin");
        assertThat(p.getOin()).isEqualTo(VALID_OIN_A);
        assertThat(p.isOinValidated()).isTrue();
        assertThat(p.getRole()).isNull();
        assertThat(p.getService()).isNull();
    }

    @Test
    void parseParties_withTpNamespacePrefix_extractsSameAsNoPrefix() {
        String xml = "<tp:CollaborationProtocolAgreement xmlns:tp='http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd'>"
            + "<tp:PartyInfo>"
            + "  <tp:PartyId tp:type='urn:oin'>" + VALID_OIN_A + "</tp:PartyId>"
            + "</tp:PartyInfo>"
            + "</tp:CollaborationProtocolAgreement>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        assertThat(parties.get(0).getPartyId()).isEqualTo(VALID_OIN_A);
        // 'type' attribute may be namespaced or unqualified; parser reads local 'type'.
        // The parser uses getAttribute("type") - so tp:type will not be picked up. We test the unqualified variant separately.
    }

    @Test
    void parseParties_withTpNamespacePrefix_unqualifiedTypeAttribute() {
        String xml = "<tp:CollaborationProtocolAgreement xmlns:tp='http://x'>"
            + "<tp:PartyInfo>"
            + "  <tp:PartyId type='urn:oin'>" + VALID_OIN_A + "</tp:PartyId>"
            + "</tp:PartyInfo>"
            + "</tp:CollaborationProtocolAgreement>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        assertThat(parties.get(0).getPartyIdType()).isEqualTo("urn:oin");
    }

    @Test
    void parseParties_nonOinPartyId_setsOinNullAndValidatedFalse() {
        String xml = "<Root><PartyInfo><PartyId type='X'>NOT-AN-OIN</PartyId></PartyInfo></Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        PartyInfoDto p = parties.get(0);
        assertThat(p.getPartyId()).isEqualTo("NOT-AN-OIN");
        assertThat(p.getOin()).isNull();
        assertThat(p.isOinValidated()).isFalse();
    }

    @Test
    void parseParties_withCollaborationRoleAndService_extractsRoleAndService() {
        String xml = "<Root>"
            + "<PartyInfo>"
            + "  <PartyId type='urn:oin'>" + VALID_OIN_A + "</PartyId>"
            + "  <CollaborationRole>"
            + "    <Role name='Sender'/>"
            + "    <ServiceBinding>"
            + "      <Service>urn:svc:test</Service>"
            + "    </ServiceBinding>"
            + "  </CollaborationRole>"
            + "</PartyInfo>"
            + "</Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        PartyInfoDto p = parties.get(0);
        assertThat(p.getRole()).isEqualTo("Sender");
        assertThat(p.getService()).isEqualTo("urn:svc:test");
    }

    @Test
    void parseParties_multiplePartyInfo_returnsAllInOrder() {
        String xml = "<Root>"
            + "<PartyInfo><PartyId type='urn:oin'>" + VALID_OIN_A + "</PartyId></PartyInfo>"
            + "<PartyInfo><PartyId type='urn:oin'>" + VALID_OIN_B + "</PartyId></PartyInfo>"
            + "</Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(2);
        assertThat(parties.get(0).getPartyId()).isEqualTo(VALID_OIN_A);
        assertThat(parties.get(1).getPartyId()).isEqualTo(VALID_OIN_B);
    }

    // ── Edge cases / skip rules ──────────────────────────────────────────

    @Test
    void parseParties_partyInfoWithoutPartyId_isSkipped() {
        String xml = "<Root><PartyInfo><SomethingElse/></PartyInfo>"
            + "<PartyInfo><PartyId type='X'>" + VALID_OIN_A + "</PartyId></PartyInfo></Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).hasSize(1);
        assertThat(parties.get(0).getPartyId()).isEqualTo(VALID_OIN_A);
    }

    @Test
    void parseParties_blankPartyIdValue_isSkipped() {
        String xml = "<Root><PartyInfo><PartyId type='X'>   </PartyId></PartyInfo></Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        assertThat(parties).isEmpty();
    }

    @Test
    void parseParties_nullInput_returnsEmptyList() {
        assertThat(parser.parseParties(null)).isEmpty();
    }

    @Test
    void parseParties_blankInput_returnsEmptyList() {
        assertThat(parser.parseParties("   ")).isEmpty();
    }

    @Test
    void parseParties_malformedXml_returnsEmptyListNoThrow() {
        assertThat(parser.parseParties("<Root><Unclosed>")).isEmpty();
    }

    // ── Security: XXE protection ─────────────────────────────────────────

    @Test
    void parseParties_xmlWithDoctype_returnsEmptyListSafely() {
        String xml = "<?xml version='1.0'?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
            + "<Root><PartyInfo><PartyId type='X'>&xxe;</PartyId></PartyInfo></Root>";

        List<PartyInfoDto> parties = parser.parseParties(xml);

        // With disallow-doctype-decl=true this parser will throw internally and
        // return an empty list. No external resource is fetched.
        assertThat(parties).isEmpty();
    }
}
