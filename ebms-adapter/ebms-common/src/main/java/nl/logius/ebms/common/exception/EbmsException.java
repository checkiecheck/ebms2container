package nl.logius.ebms.common.exception;

/**
 * Basis-uitzondering voor alle ebMS2-gerelateerde fouten.
 */
public class EbmsException extends RuntimeException {

    private final String errorCode;

    public EbmsException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EbmsException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** ebXML-foutcode, bijv. {@code SecurityFailure}, {@code ValueNotRecognized}. */
    public String getErrorCode() {
        return errorCode;
    }
}
