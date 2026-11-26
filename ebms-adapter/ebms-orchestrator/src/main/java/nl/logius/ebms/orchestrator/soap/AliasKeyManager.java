package nl.logius.ebms.orchestrator.soap;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * {@link X509KeyManager} die voor client-authenticatie altijd één vooraf gekozen key-alias
 * teruggeeft, in plaats van de JDK een willekeurige key uit de keystore te laten kiezen.
 */
final class AliasKeyManager extends X509ExtendedKeyManager {

    private final X509KeyManager delegate;
    private final String clientAlias;

    AliasKeyManager(X509KeyManager delegate, String clientAlias) {
        this.delegate = delegate;
        this.clientAlias = clientAlias;
    }

    String getClientAlias() {
        return clientAlias;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return clientAlias;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return clientAlias;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[] {clientAlias};
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.getPrivateKey(alias);
    }
}
