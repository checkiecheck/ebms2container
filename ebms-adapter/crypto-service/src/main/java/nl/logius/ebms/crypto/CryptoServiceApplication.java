package nl.logius.ebms.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

/**
 * crypto-service – geïsoleerde XML-beveiligingsservice.
 *
 * <p>Verantwoordelijkheden:
 * <ul>
 *   <li>XML-C14N (Exclusive Canonicalization conform XML-C14N 1.0 / 1.1)</li>
 *   <li>XML-DSig ondertekening en verificatie (RSA-SHA256, ECDSA-SHA256)</li>
 *   <li>XML-Enc encryptie/decryptie (AES-256-GCM, RSA-OAEP)</li>
 *   <li>Eigen KeyStore/TrustStore (PKCS12) – niet gedeeld met andere services</li>
 *   <li>Sleutel-metadata en crypto-auditlogs in PostgreSQL</li>
 * </ul>
 *
 * <p>Bouncy Castle wordt als JCE Security Provider geregistreerd bij opstarten.
 * Poort: 8082
 */
@SpringBootApplication
public class CryptoServiceApplication {

    static {
        // Registreer Bouncy Castle als JCE-provider vóór Spring context
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(CryptoServiceApplication.class, args);
    }
}
