package nl.logius.ebms.orchestrator.entity;

/** Richting van het ebMS2-bericht vanuit het perspectief van deze adapter. */
public enum MessageDirection {

    /** Bericht ontvangen van een externe partner. */
    INBOUND,

    /** Bericht te verzenden naar een externe partner. */
    OUTBOUND
}
