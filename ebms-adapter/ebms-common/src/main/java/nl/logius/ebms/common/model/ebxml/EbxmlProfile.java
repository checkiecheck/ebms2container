package nl.logius.ebms.common.model.ebxml;

/**
 * Digikoppeling ebMS2 profielen conform Koppelvlakstandaard v3.3.2.
 *
 * <pre>
 *   osb-be    – Best Effort
 *   osb-rm    – Reliable Messaging (ACK + retry)
 *   osb-be-s  – Best Effort + ondertekening (XML-DSig)
 *   osb-rm-s  – Reliable Messaging + ondertekening
 *   osb-be-e  – Best Effort + encryptie (XML-Enc)
 *   osb-rm-e  – Reliable Messaging + ondertekening + encryptie
 * </pre>
 */
public enum EbxmlProfile {

    OSB_BE("osb-be"),
    OSB_RM("osb-rm"),
    OSB_BE_S("osb-be-s"),
    OSB_RM_S("osb-rm-s"),
    OSB_BE_E("osb-be-e"),
    OSB_RM_E("osb-rm-e");

    private final String code;

    EbxmlProfile(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** Vertrekt vanuit de string-code zoals opgeslagen in de CPA. */
    public static EbxmlProfile fromCode(String code) {
        for (EbxmlProfile p : values()) {
            if (p.code.equalsIgnoreCase(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Onbekend Digikoppeling-profiel: " + code);
    }

    /** True als het profiel ACK-verwerking en retry vereist. */
    public boolean hasReliableMessaging() {
        return this == OSB_RM || this == OSB_RM_S || this == OSB_RM_E;
    }

    /** True als het profiel XML-DSig ondertekening vereist. */
    public boolean requiresSigning() {
        return this == OSB_BE_S || this == OSB_RM_S || this == OSB_BE_E || this == OSB_RM_E;
    }

    /** True als het profiel XML-Enc encryptie vereist. */
    public boolean requiresEncryption() {
        return this == OSB_BE_E || this == OSB_RM_E;
    }
}
