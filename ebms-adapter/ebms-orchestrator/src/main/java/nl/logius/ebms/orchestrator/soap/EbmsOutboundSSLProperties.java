package nl.logius.ebms.orchestrator.soap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuratie-properties voor de outbound mTLS SSLContext.
 *
 * <p>Prefix: {@code ebms.outbound.ssl}
 *
 * <p>Voorbeeld {@code application.yml}:
 * <pre>
 * ebms:
 *   outbound:
 *     ssl:
 *       keystore-path: /certs/client-keystore.p12
 *       keystore-password: ${KEYSTORE_PASSWORD}
 *       truststore-path: /certs/truststore.p12
 *       truststore-password: ${TRUSTSTORE_PASSWORD}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "ebms.outbound.ssl")
@Getter
@Setter
public class EbmsOutboundSSLProperties {

    /** Pad naar de PKCS12 keystore met het client-certificaat (private key + chain). */
    private String keystorePath;

    /** Wachtwoord voor de keystore (en de private key, ebMS2 gebruikt doorgaans hetzelfde). */
    private String keystorePassword;

    /** Pad naar de PKCS12/JKS truststore met vertrouwde partner-CA's (PKIoverheid). */
    private String truststorePath;

    /** Wachtwoord voor de truststore. */
    private String truststorePassword;
}
