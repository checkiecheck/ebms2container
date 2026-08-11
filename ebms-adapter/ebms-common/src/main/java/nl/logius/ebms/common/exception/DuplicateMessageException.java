package nl.logius.ebms.common.exception;

/**
 * Gooi wanneer een bericht al eerder ontvangen is (duplicate suppression).
 * Conform ebMS2 Reliable Messaging spec, Section 6.5.
 */
public class DuplicateMessageException extends EbmsException {

    public DuplicateMessageException(String messageId) {
        super("DUPLICATE_MESSAGE", "Bericht al ontvangen (duplicate): " + messageId);
    }
}
