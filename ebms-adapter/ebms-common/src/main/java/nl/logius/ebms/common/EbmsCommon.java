package nl.logius.ebms.common;

/**
 * Marker-klasse voor het {@code ebms-common} module.
 *
 * <p>Deze module bevat gedeelde Java-typen voor alle ebMS2-microservices:
 * <ul>
 *   <li>{@code model.ebxml} – ebXML SOAP-envelop DTO's (Fase 2)</li>
 *   <li>{@code model.cpa}   – Collaboration Protocol Agreement domeinmodel (Fase 2)</li>
 *   <li>{@code model.amqp}  – Interne AMQP-berichtmodellen (Fase 2)</li>
 *   <li>{@code util}        – OIN-validator, XML-hulpklassen (Fase 2)</li>
 *   <li>{@code exception}   – Gemeenschappelijke uitzonderingen (Fase 2)</li>
 * </ul>
 */
public final class EbmsCommon {

    private EbmsCommon() {
        // Utility klasse – niet instantiëren
    }

    /** Versie van de ebMS2-koppelvlakstandaard die wordt geïmplementeerd. */
    public static final String DIGIKOPPELING_EBMS2_VERSION = "3.3.2";

    /** Namespace voor OASIS ebXML Messaging Services. */
    public static final String EBXML_MSG_NS = "http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd";

    /** Namespace voor Digikoppeling CPA. */
    public static final String CPA_NS = "http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd";
}
