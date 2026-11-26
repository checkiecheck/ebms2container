package nl.logius.ebms.orchestrator.soap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.PartnerCertificateDto;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bouwt de outbound mTLS {@link SSLContext} op, met twee gescheiden verantwoordelijkheden:
 *
 * <ul>
 *   <li><b>Server-TLS (TrustManager):</b> het HTTPS-servercertificaat van de partner wordt
 *       gevalideerd tegen {@code truststore.p12} en (optioneel) de standaard JVM-truststore. Het
 *       CPA-partnercertificaat is een berichtlaag-certificaat en wordt alleen als trust-anchor
 *       gebruikt als {@code trust-cpa-certificates=true}.</li>
 *   <li><b>Client-authenticatie (KeyManager):</b> het eigen certificaat uit de CPA
 *       ({@code fromPartyId}) wordt via fingerprint (of issuer+serial) gekoppeld aan een key-alias
 *       in {@code keystore.p12}. Alleen als de CPA geen eigen certificaat specificeert, geldt de
 *       fallbackvolgorde {@code ebms.mtls.client-key-alias} → {@code ebms.outbound.signing-key-alias}.</li>
 * </ul>
 *
 * <p>De opgebouwde {@link SSLContext} wordt gecachet; de cache-sleutel bevat de fingerprints van de
 * CPA-certificaten en de wijzigingstijd van keystore/truststore, zodat rotatie vanzelf doorwerkt.
 */
@Component
@Slf4j
public class OutboundSslContextFactory {

    static final String CERTIFICATE_NOT_FOUND = "CERTIFICATE_NOT_FOUND";
    private static final String SERIAL_NUMBER_OID = "2.5.4.5";

    private final EbmsOutboundSSLProperties props;
    private final CpaValidationService cpaValidationService;
    private final String clientKeyAlias;
    private final String signingKeyAlias;
    private final Cache<CacheKey, SSLContext> cache;

    public OutboundSslContextFactory(
            EbmsOutboundSSLProperties props,
            CpaValidationService cpaValidationService,
            @Value("${ebms.mtls.client-key-alias:orchestrator-key}") String clientKeyAlias,
            @Value("${ebms.outbound.signing-key-alias:signing-key}") String signingKeyAlias) {
        this.props = props;
        this.cpaValidationService = cpaValidationService;
        this.clientKeyAlias = clientKeyAlias;
        this.signingKeyAlias = signingKeyAlias;
        this.cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofSeconds(Math.max(1, props.getContextCacheTtlSeconds())))
            .build();
    }

    /**
     * @param cpaId       CPA-identifier
     * @param fromPartyId eigen partij (bepaalt het mTLS-clientcertificaat); mag null zijn
     * @param toPartyId   partij van de ontvanger
     * @throws EbmsException {@code MTLS_TRUST_ERROR} / {@code MTLS_KEY_ERROR} bij configuratiefouten;
     *                        CPA-fouten anders dan {@code CERTIFICATE_NOT_FOUND} worden doorgegeven
     */
    public SSLContext getSslContext(String cpaId, String fromPartyId, String toPartyId) {
        boolean hasKeystore = hasKeystore();
        List<X509Certificate> ownCerts = hasKeystore
            ? parse(lookupOptional(cpaId, fromPartyId, "eigen partij"), "eigen partij")
            : List.of();
        List<X509Certificate> cpaTrustCerts = props.isTrustCpaCertificates()
            ? parse(lookupOptional(cpaId, toPartyId, "tegenpartij"), "tegenpartij")
            : List.of();

        CacheKey key = new CacheKey(
            fromPartyId, fingerprints(ownCerts), fingerprints(cpaTrustCerts),
            fileSignature(hasKeystore ? props.getKeystorePath() : null),
            fileSignature(props.getTruststorePath()),
            props.isIncludeJvmDefaultTrust(), props.isEnforceClientOinMatch());

        return cache.get(key, k -> {
            SSLContext context = build(ownCerts, cpaTrustCerts, hasKeystore, fromPartyId);
            log.info("[OUTBOUND] mTLS SSLContext opgebouwd: cpaId={} fromPartyId={} toPartyId={} "
                    + "cpaOwnCerts={} cpaTrustCerts={} jvmTrust={}",
                cpaId, fromPartyId, toPartyId, ownCerts.size(), cpaTrustCerts.size(),
                props.isIncludeJvmDefaultTrust());
            return context;
        });
    }

    // ── Opbouw ────────────────────────────────────────────────────────────────

    private SSLContext build(List<X509Certificate> ownCerts, List<X509Certificate> cpaTrustCerts,
                             boolean hasKeystore, String fromPartyId) {
        KeyManager[] keyManagers = null;
        if (hasKeystore) {
            try {
                keyManagers = new KeyManager[] {buildKeyManager(ownCerts, fromPartyId)};
            } catch (EbmsException e) {
                throw e;
            } catch (Exception e) {
                throw new EbmsException("MTLS_KEY_ERROR",
                    "Kon client-keystore niet laden/gebruiken: " + e.getMessage(), e);
            }
        } else {
            log.warn("[OUTBOUND] Geen client-keystore geconfigureerd; server mTLS-authenticatie kan mislukken");
        }

        TrustManager[] trustManagers;
        try {
            trustManagers = new TrustManager[] {buildTrustManager(cpaTrustCerts)};
        } catch (EbmsException e) {
            throw e;
        } catch (Exception e) {
            throw new EbmsException("MTLS_TRUST_ERROR",
                "Kon server-TLS trust niet opbouwen: " + e.getMessage(), e);
        }

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (Exception e) {
            throw new EbmsException("MTLS_TRUST_ERROR", "Kon SSLContext niet initialiseren: " + e.getMessage(), e);
        }
    }

    private X509TrustManager buildTrustManager(List<X509Certificate> cpaTrustCerts) throws Exception {
        List<X509TrustManager> delegates = new ArrayList<>();

        String truststorePath = props.getTruststorePath();
        if (truststorePath != null && !truststorePath.isBlank()) {
            File truststoreFile = new File(truststorePath);
            if (truststoreFile.isFile()) {
                String password = props.getTruststorePassword();
                KeyStore truststore = KeyStore.getInstance(truststoreFile,
                    password == null || password.isBlank() ? null : password.toCharArray());
                delegates.add(x509TrustManager(truststore));
            } else {
                // Pad is ingesteld maar bestand ontbreekt (bv. nog niet aangemaakt): niet fataal
                // zolang er een andere trust-bron is; corrupt/onleesbaar blijft wel een fout.
                log.warn("[OUTBOUND] Truststore {} bestaat niet; wordt overgeslagen", truststorePath);
            }
        }
        if (props.isIncludeJvmDefaultTrust()) {
            delegates.add(x509TrustManager(null));
        }
        if (!cpaTrustCerts.isEmpty()) {
            KeyStore cpaStore = KeyStore.getInstance("PKCS12");
            cpaStore.load(null, null);
            int i = 0;
            for (X509Certificate cert : cpaTrustCerts) {
                cpaStore.setCertificateEntry("cpa-partner-" + i++, cert);
            }
            delegates.add(x509TrustManager(cpaStore));
        }

        if (delegates.isEmpty()) {
            throw new EbmsException("MTLS_TRUST_ERROR",
                "Geen server-TLS trust-bron: configureer ebms.outbound.ssl.truststore-path of zet "
                    + "ebms.outbound.ssl.include-jvm-default-trust=true");
        }
        return delegates.size() == 1 ? delegates.get(0) : new CompositeX509TrustManager(delegates);
    }

    private X509TrustManager x509TrustManager(KeyStore keyStore) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("Geen X509TrustManager beschikbaar");
    }

    private AliasKeyManager buildKeyManager(List<X509Certificate> ownCerts, String fromPartyId) throws Exception {
        char[] password = props.getKeystorePassword().toCharArray();
        KeyStore keystore = KeyStore.getInstance(new File(props.getKeystorePath()), password);

        String alias = selectAlias(keystore, ownCerts);
        checkOin((X509Certificate) keystore.getCertificate(alias), alias, fromPartyId);

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keystore, password);
        for (KeyManager km : factory.getKeyManagers()) {
            if (km instanceof X509KeyManager x509) {
                return new AliasKeyManager(x509, alias);
            }
        }
        throw new IllegalStateException("Geen X509KeyManager beschikbaar");
    }

    /** Kiest de keystore-alias: CPA-match eerst, anders geconfigureerde fallback-aliassen. */
    String selectAlias(KeyStore keystore, List<X509Certificate> ownCerts) throws Exception {
        List<String> keyAliases = new ArrayList<>();
        for (String alias : Collections.list(keystore.aliases())) {
            if (keystore.isKeyEntry(alias)) {
                keyAliases.add(alias);
            }
        }
        Collections.sort(keyAliases);

        List<String> preferred = new ArrayList<>();
        for (String candidate : new String[] {clientKeyAlias, signingKeyAlias}) {
            if (candidate != null && !candidate.isBlank() && !preferred.contains(candidate)) {
                preferred.add(candidate);
            }
        }

        List<String> matches = new ArrayList<>();
        for (String alias : keyAliases) {
            if (keystore.getCertificate(alias) instanceof X509Certificate keyCert
                    && ownCerts.stream().anyMatch(own -> sameCertificate(own, keyCert))) {
                matches.add(alias);
            }
        }
        if (!matches.isEmpty()) {
            String chosen = preferred.stream().filter(matches::contains).findFirst().orElse(matches.get(0));
            log.info("[OUTBOUND] mTLS client-key via CPA-certificaat gekozen: alias={} (matches={})", chosen, matches);
            return chosen;
        }

        if (!ownCerts.isEmpty()) {
            log.warn("[OUTBOUND] Eigen CPA-certificaat(en) ({}) niet gevonden in keystore; "
                + "terugval op geconfigureerde alias", ownCerts.size());
        }
        for (String candidate : preferred) {
            if (keyAliases.contains(candidate)) {
                log.info("[OUTBOUND] mTLS client-key via fallback-alias gekozen: alias={}", candidate);
                return candidate;
            }
        }
        throw new EbmsException("MTLS_KEY_ERROR",
            "Geen mTLS client-key gevonden: geen CPA-match en geen van de aliassen " + preferred
                + " is een key-entry in de keystore (aanwezig: " + keyAliases + ")");
    }

    private void checkOin(X509Certificate cert, String alias, String fromPartyId) {
        if (fromPartyId == null || fromPartyId.isBlank()) {
            return;
        }
        String oin = subjectSerialNumber(cert);
        if (oin == null || oin.trim().equals(fromPartyId.trim())) {
            return;
        }
        String message = "OIN in clientcertificaat (alias=" + alias + ", serialNumber=" + oin
            + ") komt niet overeen met eigen partij " + fromPartyId;
        if (props.isEnforceClientOinMatch()) {
            throw new EbmsException("MTLS_KEY_ERROR", message);
        }
        log.warn("[OUTBOUND] {}", message);
    }

    static String subjectSerialNumber(X509Certificate cert) {
        try {
            LdapName name = new LdapName(cert.getSubjectX500Principal().getName(X500Principal.RFC1779));
            for (Rdn rdn : name.getRdns()) {
                String type = rdn.getType();
                if ("SERIALNUMBER".equalsIgnoreCase(type) || type.endsWith(SERIAL_NUMBER_OID)) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception e) {
            log.debug("[OUTBOUND] Subject van clientcertificaat niet te parsen: {}", e.getMessage());
        }
        return null;
    }

    // ── CPA-lookup en certificaat-helpers ─────────────────────────────────────

    private List<PartnerCertificateDto> lookupOptional(String cpaId, String partyId, String purpose) {
        if (partyId == null || partyId.isBlank()) {
            return List.of();
        }
        try {
            return cpaValidationService.getPartnerCertificates(cpaId, partyId);
        } catch (EbmsException e) {
            if (CERTIFICATE_NOT_FOUND.equals(e.getErrorCode())) {
                log.warn("[OUTBOUND] Geen CPA-certificaat voor {} (cpaId={} partyId={}); doorgaan zonder",
                    purpose, cpaId, partyId);
                return List.of();
            }
            throw e;
        }
    }

    private List<X509Certificate> parse(List<PartnerCertificateDto> dtos, String purpose) {
        List<X509Certificate> certs = new ArrayList<>();
        for (PartnerCertificateDto dto : dtos) {
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                certs.add((X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(dto.getCertificatePem().getBytes(StandardCharsets.UTF_8))));
            } catch (Exception e) {
                log.warn("[OUTBOUND] CPA-certificaat ({}) alias={} is niet te parsen en wordt genegeerd: {}",
                    purpose, dto.getCertificateAlias(), e.getMessage());
            }
        }
        return certs;
    }

    private static boolean sameCertificate(X509Certificate a, X509Certificate b) {
        return fingerprint(a).equals(fingerprint(b))
            || (a.getIssuerX500Principal().equals(b.getIssuerX500Principal())
                && a.getSerialNumber().equals(b.getSerialNumber()));
    }

    private static Set<String> fingerprints(List<X509Certificate> certs) {
        Set<String> result = new TreeSet<>();
        certs.forEach(c -> result.add(fingerprint(c)));
        return result;
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Fingerprint berekenen mislukt", e);
        }
    }

    private boolean hasKeystore() {
        return props.getKeystorePath() != null && !props.getKeystorePath().isBlank()
            && props.getKeystorePassword() != null && !props.getKeystorePassword().isBlank();
    }

    private static String fileSignature(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        File file = new File(path);
        return path + ":" + file.lastModified() + ":" + file.length();
    }

    private record CacheKey(String fromPartyId, Set<String> ownCertFingerprints,
                            Set<String> cpaTrustFingerprints, String keystoreSignature,
                            String truststoreSignature, boolean jvmTrust, boolean enforceOin) {
    }
}
