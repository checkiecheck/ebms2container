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
 *       include-jvm-default-trust: true
 *       trust-cpa-certificates: false
 * </pre>
 *
 * <p>De truststore valideert het <em>server-TLS</em>-certificaat van de partner. Het
 * partnercertificaat uit de CPA is een berichtlaag-certificaat (XML-DSig/XML-Enc) en is daarom
 * standaard geen TLS trust-anchor.
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

    /** Pad naar de PKCS12/JKS truststore met vertrouwde server-TLS CA's (PKIoverheid e.d.). */
    private String truststorePath;

    /** Wachtwoord voor de truststore. */
    private String truststorePassword;

    /** Voeg de standaard JVM-truststore (cacerts) toe, zodat publieke CA's (bv. DigiCert) werken. */
    private boolean includeJvmDefaultTrust = true;

    /**
     * Vertrouw daarnaast de CPA-partnercertificaten van de tegenpartij als TLS trust-anchor
     * (alleen bedoeld voor zelf-ondertekende testpartners).
     */
    private boolean trustCpaCertificates = false;

    /** Wijs af (i.p.v. alleen waarschuwen) als de OIN in het clientcertificaat niet bij de eigen partij past. */
    private boolean enforceClientOinMatch = false;

    /** Time-to-live van de gecachete SSLContext in seconden. */
    private long contextCacheTtlSeconds = 600;
}
