package nl.logius.ebms.orchestrator.soap;

import nl.logius.ebms.orchestrator.soap.TestPki.Identity;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeX509TrustManagerTest {

    private static X509TrustManager trusting(X509Certificate... anchors) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(TestPki.trustStore(anchors));
        return (X509TrustManager) factory.getTrustManagers()[0];
    }

    @Test
    void acceptsChainTrustedByAnyDelegate() throws Exception {
        Identity caA = TestPki.newCa("ca-a");
        Identity caB = TestPki.newCa("ca-b");
        Identity serverB = TestPki.server(caB, "server-b", "localhost");

        CompositeX509TrustManager composite =
            new CompositeX509TrustManager(List.of(trusting(caA.cert()), trusting(caB.cert())));

        composite.checkServerTrusted(serverB.chain(), "ECDHE_ECDSA");
    }

    @Test
    void rejectsChainNoDelegateTrusts() throws Exception {
        Identity trusted = TestPki.newCa("trusted");
        Identity rogueServer = TestPki.server(TestPki.newCa("rogue"), "rogue", "localhost");

        CompositeX509TrustManager composite = new CompositeX509TrustManager(List.of(trusting(trusted.cert())));

        assertThatThrownBy(() -> composite.checkServerTrusted(rogueServer.chain(), "ECDHE_ECDSA"))
            .isInstanceOf(CertificateException.class);
    }

    @Test
    void acceptedIssuersIsUnionOfDelegates() throws Exception {
        Identity caA = TestPki.newCa("ca-a");
        Identity caB = TestPki.newCa("ca-b");

        CompositeX509TrustManager composite =
            new CompositeX509TrustManager(List.of(trusting(caA.cert()), trusting(caB.cert())));

        assertThat(composite.getAcceptedIssuers()).containsExactlyInAnyOrder(caA.cert(), caB.cert());
    }

    @Test
    void requiresAtLeastOneDelegate() {
        assertThatThrownBy(() -> new CompositeX509TrustManager(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
