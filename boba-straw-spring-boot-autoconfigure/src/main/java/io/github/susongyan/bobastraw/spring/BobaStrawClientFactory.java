package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClusterClient;
import io.github.susongyan.bobastraw.BobaStrawSentinelClient;
import io.github.susongyan.bobastraw.BobaStrawTlsOptions;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.core.io.ResourceLoader;

/** Creates owned clients; TLS material is validated before any client resources are allocated. */
final class BobaStrawClientFactory {
    private final ResourceLoader resources;

    BobaStrawClientFactory(ResourceLoader resources) {
        this.resources = resources;
    }

    AutoCloseable create(BobaStrawClientProperties properties) {
        validate(properties);
        BobaStrawTlsOptions tls = tls(properties.getTls());
        BobaStrawTlsOptions sentinelTls = tls(properties.getSentinelTls());
        switch (properties.getMode()) {
            case CLUSTER:
                return BobaStrawClusterClient.builder()
                    .seeds(properties.getNodes().toArray(new String[0]))
                    .credentials(properties.getUsername(), properties.getPassword())
                    .protocol(properties.getProtocol()).commandTimeout(properties.getCommandTimeout())
                    .topologyRefreshInterval(refreshInterval(properties)).tls(tls).build();
            case SENTINEL:
                BobaStrawSentinelClient.Builder builder = BobaStrawSentinelClient.builder()
                    .masterName(properties.getMasterName())
                    .credentials(properties.getUsername(), properties.getPassword())
                    .sentinelCredentials(properties.getSentinelUsername(), properties.getSentinelPassword())
                    .protocol(properties.getProtocol()).commandTimeout(properties.getCommandTimeout())
                    .discoveryTimeout(properties.getDiscoveryTimeout())
                    .topologyRefreshInterval(refreshInterval(properties))
                    .tls(tls).sentinelTls(sentinelTls);
                for (String node : properties.getNodes()) {
                    URI endpoint = endpoint(node);
                    String host = endpoint.getHost();
                    if (host.startsWith("[")) {
                        host = host.substring(1, host.length() - 1);
                    }
                    builder.sentinel(host, endpoint.getPort());
                }
                return builder.build();
            default:
                BobaStrawClient.Builder standalone = BobaStrawClient.builder()
                    .uri(properties.getUri()).protocol(properties.getProtocol())
                    .commandTimeout(properties.getCommandTimeout()).tls(tls);
                if (properties.getUsername() != null || properties.getPassword() != null) {
                    standalone.credentials(properties.getUsername(), properties.getPassword());
                }
                return standalone.build();
        }
    }

    static void validate(BobaStrawClientProperties properties) {
        if (properties == null || properties.getMode() == null || properties.getProtocol() == null) {
            throw new IllegalArgumentException("Client mode and protocol are required");
        }
        positive(properties.getCommandTimeout());
        positive(properties.getDiscoveryTimeout());
        positive(refreshInterval(properties));
        if (properties.getTls() == null || properties.getSentinelTls() == null) {
            throw new IllegalArgumentException("TLS settings must not be null");
        }
        if (properties.getMode() != BobaStrawClientProperties.Mode.STANDALONE) {
            if (properties.getNodes() == null || properties.getNodes().isEmpty()) {
                throw new IllegalArgumentException("Topology requires at least one node");
            }
            for (String node : properties.getNodes()) {
                endpoint(node);
            }
        }
        if (properties.getMode() == BobaStrawClientProperties.Mode.SENTINEL
            && (properties.getMasterName() == null || properties.getMasterName().trim().isEmpty())) {
            throw new IllegalArgumentException("Sentinel master-name is required");
        }
        if (properties.getMode() != BobaStrawClientProperties.Mode.SENTINEL
            && (properties.getSentinelTls().isEnabled() || properties.getSentinelUsername() != null
                || properties.getSentinelPassword() != null)) {
            throw new IllegalArgumentException("Sentinel settings require Sentinel mode");
        }
    }

    private static void positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Client timeouts and refresh interval must be positive");
        }
    }

    private static Duration refreshInterval(BobaStrawClientProperties properties) {
        return properties.getTopologyRefreshInterval() != null ? properties.getTopologyRefreshInterval()
            : Duration.ofSeconds(properties.getMode() == BobaStrawClientProperties.Mode.CLUSTER ? 30 : 1);
    }

    private static URI endpoint(String node) {
        try {
            URI endpoint = URI.create("redis://" + node);
            if (endpoint.getHost() == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !endpoint.getPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
            return endpoint;
        } catch (RuntimeException error) {
            // Do not echo an invalid endpoint: it may contain a password.
            throw new IllegalArgumentException("Nodes must be host:port or [IPv6]:port without credentials");
        }
    }

    private BobaStrawTlsOptions tls(BobaStrawClientProperties.Tls properties) {
        if (properties == null) {
            throw new IllegalArgumentException("TLS settings must not be null");
        }
        if (!properties.isEnabled()) {
            if (properties.getTrustStore() != null || properties.getKeyStore() != null) {
                throw new IllegalArgumentException("TLS stores require tls.enabled=true");
            }
            return null;
        }
        try {
            if (properties.getTrustStore() == null && properties.getKeyStore() == null) {
                return BobaStrawTlsOptions.builder().handshakeTimeout(properties.getHandshakeTimeout()).build();
            }
            TrustManagerFactory trust = null;
            KeyManagerFactory keys = null;
            if (properties.getTrustStore() != null) {
                trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store(properties.getTrustStore(), properties.getTrustStorePassword(),
                    properties.getStoreType()));
            }
            if (properties.getKeyStore() != null) {
                keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store(properties.getKeyStore(), properties.getKeyStorePassword(),
                    properties.getStoreType()), password(properties.getKeyStorePassword()));
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys == null ? null : keys.getKeyManagers(),
                trust == null ? null : trust.getTrustManagers(), null);
            return BobaStrawTlsOptions.builder().sslContext(context)
                .handshakeTimeout(properties.getHandshakeTimeout()).build();
        } catch (Exception error) {
            // Resource URLs and provider errors can expose secrets. Fail closed without echoing them.
            throw new IllegalArgumentException("Cannot initialize TLS stores or handshake settings");
        }
    }

    private KeyStore store(String location, String secret, String type) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream input = resources.getResource(location).getInputStream()) {
            store.load(input, password(secret));
        }
        return store;
    }

    private char[] password(String value) {
        return value == null ? null : value.toCharArray();
    }
}
