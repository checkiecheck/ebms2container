package nl.logius.ebms.orchestrator.soap;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Genereert in-memory test-PKI (CA's, server- en clientcertificaten) voor de mTLS-tests. */
final class TestPki {

    static final char[] PASSWORD = "changeit".toCharArray();
    private static long serial = System.nanoTime();

    record Identity(PrivateKey key, X509Certificate cert, X509Certificate[] chain) {
    }

    private TestPki() {
    }

    static Identity newCa(String cn) throws Exception {
        KeyPair keyPair = newKeyPair();
        X500Name subject = new X500Name("CN=" + cn);
        X509Certificate cert = build(null, subject, keyPair.getPublic(), keyPair.getPrivate(), true, null);
        return new Identity(keyPair.getPrivate(), cert, new X509Certificate[] {cert});
    }

    /** Servercertificaat met SAN's, uitgegeven door {@code ca}. */
    static Identity server(Identity ca, String cn, String... dnsNames) throws Exception {
        GeneralName[] names = new GeneralName[dnsNames.length];
        for (int i = 0; i < dnsNames.length; i++) {
            String name = dnsNames[i];
            boolean ip = name.matches("[0-9.]+");
            names[i] = new GeneralName(ip ? GeneralName.iPAddress : GeneralName.dNSName, name);
        }
        return issue(ca, "CN=" + cn, new GeneralNames(names));
    }

    /** Clientcertificaat met OIN als subject-serialNumber (PKIoverheid-stijl). */
    static Identity client(Identity ca, String cn, String oin) throws Exception {
        return issue(ca, "CN=" + cn + ", SERIALNUMBER=" + oin, null);
    }

    static Identity selfSigned(String cn, String... dnsNames) throws Exception {
        KeyPair keyPair = newKeyPair();
        X500Name subject = new X500Name("CN=" + cn);
        GeneralName[] names = new GeneralName[dnsNames.length];
        for (int i = 0; i < dnsNames.length; i++) {
            names[i] = new GeneralName(dnsNames[i].matches("[0-9.]+") ? GeneralName.iPAddress : GeneralName.dNSName,
                dnsNames[i]);
        }
        X509Certificate cert = build(null, subject, keyPair.getPublic(), keyPair.getPrivate(), false,
            names.length == 0 ? null : new GeneralNames(names));
        return new Identity(keyPair.getPrivate(), cert, new X509Certificate[] {cert});
    }

    private static Identity issue(Identity ca, String subjectDn, GeneralNames sans) throws Exception {
        KeyPair keyPair = newKeyPair();
        X509Certificate cert = build(ca.cert(), new X500Name(subjectDn), keyPair.getPublic(), ca.key(), false, sans);
        return new Identity(keyPair.getPrivate(), cert, new X509Certificate[] {cert, ca.cert()});
    }

    private static X509Certificate build(X509Certificate issuerCert, X500Name subject, java.security.PublicKey publicKey,
                                         PrivateKey signer, boolean ca, GeneralNames sans) throws Exception {
        Date from = new Date(System.currentTimeMillis() - 3_600_000L);
        Date until = new Date(System.currentTimeMillis() + 86_400_000L);
        BigInteger serialNumber = BigInteger.valueOf(serial++);
        JcaX509v3CertificateBuilder builder = issuerCert == null
            ? new JcaX509v3CertificateBuilder(subject, serialNumber, from, until, subject, publicKey)
            : new JcaX509v3CertificateBuilder(issuerCert, serialNumber, from, until, subject, publicKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(
            ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        if (sans != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, sans);
        }
        return new JcaX509CertificateConverter().getCertificate(
            builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer)));
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static KeyStore keyStore(Map<String, Identity> keyEntries, Map<String, X509Certificate> certEntries)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        for (Map.Entry<String, Identity> entry : keyEntries.entrySet()) {
            store.setKeyEntry(entry.getKey(), entry.getValue().key(), PASSWORD, entry.getValue().chain());
        }
        for (Map.Entry<String, X509Certificate> entry : certEntries.entrySet()) {
            store.setCertificateEntry(entry.getKey(), entry.getValue());
        }
        return store;
    }

    static KeyStore trustStore(X509Certificate... certs) throws Exception {
        Map<String, X509Certificate> entries = new LinkedHashMap<>();
        for (int i = 0; i < certs.length; i++) {
            entries.put("trust-" + i, certs[i]);
        }
        return keyStore(Map.of(), entries);
    }

    static Path write(KeyStore store, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        return file;
    }

    static String pem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    }
}
