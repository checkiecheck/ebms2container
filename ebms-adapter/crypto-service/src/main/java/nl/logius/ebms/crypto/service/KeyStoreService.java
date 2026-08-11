package nl.logius.ebms.crypto.service;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Beheert de PKCS12 KeyStore voor de crypto-service.
 *
 * <p>Verantwoordelijkheden:
 * <ul>
 *   <li>Laden van de KeyStore bij opstarten</li>
 *   <li>Ophalen van private sleutels en certificaten op alias</li>
 *   <li>Listing van beschikbare alias-namen</li>
 * </ul>
 *
 * <p>Indien de KeyStore-file nog niet bestaat (verse deployment), wordt een
 * lege KeyStore in geheugen aangemaakt. Voeg sleutels toe via keytool.
 */
@Service
@Slf4j
public class KeyStoreService {

    @Value("${ebms.crypto.keystore.path}")
    private String keystorePath;

    @Value("${ebms.crypto.keystore.password}")
    private String keystorePassword;

    @Value("${ebms.crypto.keystore.type:PKCS12}")
    private String keystoreType;

    private KeyStore keyStore;

    @PostConstruct
    public void init() {
        try {
            keyStore = KeyStore.getInstance(keystoreType, new BouncyCastleProvider());
            Path path = Path.of(keystorePath);

            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    keyStore.load(is, keystorePassword.toCharArray());
                    log.info("KeyStore geladen van {}: {} entries", keystorePath, keyStore.size());
                }
            } else {
                // Verse deployment: initialiseer lege KeyStore
                keyStore.load(null, keystorePassword.toCharArray());
                log.warn("KeyStore niet aangetroffen op {}. Lege KeyStore geïnitialiseerd. " +
                    "Voeg sleutels toe via keytool.", keystorePath);
            }
        } catch (Exception e) {
            // Niet fataal bij opstarten: lege KeyStore als fallback
            log.error("Fout bij laden KeyStore ({}): {}. Fallback naar lege KeyStore.",
                keystorePath, e.getMessage());
            try {
                keyStore = KeyStore.getInstance(keystoreType, new BouncyCastleProvider());
                keyStore.load(null, keystorePassword.toCharArray());
            } catch (Exception fallbackEx) {
                throw new IllegalStateException("KeyStore kon niet geïnitialiseerd worden", fallbackEx);
            }
        }
    }

    /**
     * Haalt de private sleutel op uit de KeyStore voor de gegeven alias.
     *
     * @param alias KeyStore-alias van het sleutelpaar
     * @return de {@link PrivateKey}
     * @throws KeyStoreException als de alias niet bestaat
     */
    public PrivateKey getPrivateKey(String alias) throws Exception {
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
            keyStore.getEntry(alias,
                new KeyStore.PasswordProtection(keystorePassword.toCharArray()));
        if (entry == null) {
            throw new KeyStoreException("Alias niet gevonden in KeyStore: " + alias);
        }
        return entry.getPrivateKey();
    }

    /**
     * Haalt het X.509-certificaat op voor de gegeven alias.
     */
    public X509Certificate getCertificate(String alias) throws Exception {
        Certificate cert = keyStore.getCertificate(alias);
        if (cert == null) {
            throw new KeyStoreException("Certificaat niet gevonden voor alias: " + alias);
        }
        return (X509Certificate) cert;
    }

    /**
     * Haalt de certificaatketen op voor de gegeven alias.
     */
    public X509Certificate[] getCertificateChain(String alias) throws Exception {
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null) return new X509Certificate[0];
        X509Certificate[] x509Chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            x509Chain[i] = (X509Certificate) chain[i];
        }
        return x509Chain;
    }

    /**
     * Geeft een lijst van alle beschikbare alias-namen in de KeyStore.
     */
    public List<String> listAliases() throws KeyStoreException {
        List<String> aliases = new ArrayList<>();
        Enumeration<String> en = keyStore.aliases();
        while (en.hasMoreElements()) {
            aliases.add(en.nextElement());
        }
        return aliases;
    }

    /** True als de alias beschikbaar is in de KeyStore. */
    public boolean hasAlias(String alias) throws KeyStoreException {
        return keyStore.containsAlias(alias);
    }
}
