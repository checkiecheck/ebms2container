package nl.logius.ebms.common.exception;

/**
 * Gooi bij een XML-beveiligingsfout (signing, verification, encryptie, decryptie).
 */
public class XmlSecurityException extends EbmsException {

    public XmlSecurityException(String message) {
        super("SecurityFailure", message);
    }

    public XmlSecurityException(String message, Throwable cause) {
        super("SecurityFailure", message, cause);
    }
}
