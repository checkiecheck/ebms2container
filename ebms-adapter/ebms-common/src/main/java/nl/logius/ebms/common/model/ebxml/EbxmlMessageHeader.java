package nl.logius.ebms.common.model.ebxml;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/**
 * Gedeserialiseerde representatie van het ebXML MessageHeader SOAP-header-element
 * conform OASIS ebXML Messaging Services v2.0, Section 3.
 *
 * <p>Dit object wordt na SOAP-parsing gebruikt als interne DTO door de orchestrator,
 * cpa-service en crypto-service.
 *
 * <pre>
 * SOAP:Header
 *   eb:MessageHeader (SOAP:mustUnderstand="1" eb:version="2.0")
 *     eb:From
 *       eb:PartyId [1..*]
 *       eb:Role?
 *     eb:To
 *       eb:PartyId [1..*]
 *       eb:Role?
 *     eb:CPAId
 *     eb:ConversationId
 *     eb:Service
 *     eb:Action
 *     eb:MessageInfo
 *       eb:Timestamp
 *       eb:MessageId
 *       eb:RefToMessageId?
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbxmlMessageHeader {

    // ── Identificatie ──────────────────────────────────────────────────────

    /** Verwijzing naar de Collaboration Protocol Agreement. */
    @NotBlank
    private String cpaId;

    /** Uniek per uitwisseling; groepeert alle gerelateerde berichten. */
    @NotBlank
    private String conversationId;

    // ── From ───────────────────────────────────────────────────────────────

    @NotEmpty
    @Valid
    private List<PartyId> from;

    /** Optionele rol van de verzendende partij (conform CPA). */
    private String fromRole;

    // ── To ─────────────────────────────────────────────────────────────────

    @NotEmpty
    @Valid
    private List<PartyId> to;

    /** Optionele rol van de ontvangende partij (conform CPA). */
    private String toRole;

    // ── Routing ────────────────────────────────────────────────────────────

    @NotNull
    @Valid
    private ServiceType service;

    /** Business-actie (bijv. "vraag", "antwoord"). */
    @NotBlank
    private String action;

    // ── MessageInfo ────────────────────────────────────────────────────────

    @NotNull
    @Valid
    private MessageInfo messageInfo;

    // ── Optioneel ──────────────────────────────────────────────────────────

    /** Aanwezig bij rm-profielen wanneer verzender een ACK vereist. */
    private AckRequested ackRequested;

    /**
     * Digikoppeling-profiel geëxtraheerd uit de CPA-lookup.
     * Null bij eerste ontvangst; ingevuld na CPA-check.
     */
    private EbxmlProfile profile;

    /**
     * OIN van de authenticerende partij, geëxtraheerd uit de
     * {@code X-Forwarded-Client-OIN} mTLS-header door de ingress proxy.
     */
    private String clientOin;
}
