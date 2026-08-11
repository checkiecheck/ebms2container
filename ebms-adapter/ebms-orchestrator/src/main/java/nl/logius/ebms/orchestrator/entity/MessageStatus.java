package nl.logius.ebms.orchestrator.entity;

/**
 * Statuswaarden voor de Reliable Messaging lifecycle van een ebMS2-bericht.
 * Opgeslagen als STRING in de database (kolom: status).
 */
public enum MessageStatus {

    /** Ontvangen maar nog niet verwerkt. */
    RECEIVED,

    /** Wordt momenteel verwerkt door de orchestrator. */
    PROCESSING,

    /** Bericht verzonden naar de partner; wacht op ebMS2 Acknowledgment (rm-profielen). */
    SENT,

    /** Succesvol afgeleverd aan de backoffice / doorstuursysteem. */
    DELIVERED,

    /** ACK ontvangen van de ontvangende partij (alleen bij rm-profielen). */
    ACKNOWLEDGED,

    /** Fout opgetreden; max retries bereikt (Reliable Messaging exhausted). */
    FAILED,

    /** Duplicaat gedetecteerd via duplicate-elimination window. */
    DUPLICATE
}
