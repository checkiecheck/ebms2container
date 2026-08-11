package nl.logius.ebms.common.exception;

/**
 * Gooi wanneer een CPA niet gevonden wordt (HTTP 404 equivalent).
 */
public class CpaNotFoundException extends EbmsException {

    public CpaNotFoundException(String cpaId) {
        super("CPA_NOT_FOUND", "CPA niet gevonden: " + cpaId);
    }
}
