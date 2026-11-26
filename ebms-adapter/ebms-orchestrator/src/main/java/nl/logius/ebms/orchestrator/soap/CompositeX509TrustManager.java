package nl.logius.ebms.orchestrator.soap;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Combineert meerdere {@link X509TrustManager}s: een keten is betrouwbaar zodra <em>een</em> van
 * de delegates hem accepteert.
 *
 * <p>Bewust een gewone {@link X509TrustManager} (geen {@code X509ExtendedTrustManager}): de JDK
 * wikkelt hem dan zelf in en past daarmee hostname-verificatie (endpoint identification) en
 * algoritme-constraints toe.
 */
final class CompositeX509TrustManager implements X509TrustManager {

    private final List<X509TrustManager> delegates;

    CompositeX509TrustManager(List<X509TrustManager> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("Minimaal één TrustManager vereist");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain, authType, true);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain, authType, false);
    }

    private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
        List<CertificateException> failures = new ArrayList<>();
        for (X509TrustManager delegate : delegates) {
            try {
                if (client) {
                    delegate.checkClientTrusted(chain, authType);
                } else {
                    delegate.checkServerTrusted(chain, authType);
                }
                return;
            } catch (CertificateException e) {
                failures.add(e);
            }
        }
        CertificateException combined = new CertificateException(
            "Certificaatketen wordt door geen enkele trust-bron vertrouwd (" + failures.size() + " bron(nen)): "
                + failures.get(0).getMessage());
        failures.forEach(combined::addSuppressed);
        throw combined;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        List<X509Certificate> issuers = new ArrayList<>();
        for (X509TrustManager delegate : delegates) {
            issuers.addAll(List.of(delegate.getAcceptedIssuers()));
        }
        return issuers.toArray(new X509Certificate[0]);
    }
}
