package io.github.susongyan.bobastraw;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Immutable TLS policy with endpoint verification. The default context verifies certificate trust;
 * applications supplying a custom context are responsible for its trust manager policy.
 */
public final class BobaStrawTlsOptions {
    private final SSLContext context;
    private final Duration handshakeTimeout;
    private final String[] protocols;

    private BobaStrawTlsOptions(Builder builder) {
        context = builder.context == null ? defaultContext() : builder.context;
        handshakeTimeout = builder.handshakeTimeout;
        protocols = builder.protocols == null ? null : builder.protocols.clone();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BobaStrawTlsOptions defaults() {
        return builder().build();
    }

    public Duration handshakeTimeout() {
        return handshakeTimeout;
    }

    private static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Default TLS context is unavailable", error);
        }
    }

    /** Internal transport factory; custom SSLContext owners must not mutate it after configuration. */
    public SSLEngine createEngine(String host, int port) {
        SSLEngine engine = context.createSSLEngine(host, port);
        engine.setUseClientMode(true);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (protocols != null) {
            parameters.setProtocols(protocols.clone());
        } else {
            List<String> enabled = new ArrayList<String>();
            List<String> supported = Arrays.asList(engine.getSupportedProtocols());
            for (String protocol : new String[] {"TLSv1.3", "TLSv1.2"}) {
                if (supported.contains(protocol)) {
                    enabled.add(protocol);
                }
            }
            parameters.setProtocols(enabled.toArray(new String[enabled.size()]));
        }
        engine.setSSLParameters(parameters);
        return engine;
    }

    public static final class Builder {
        private SSLContext context;
        private Duration handshakeTimeout = Duration.ofSeconds(5);
        private String[] protocols;

        private Builder() {
        }

        /** Supply a trusted CA and optional client identity using standard JDK key/trust managers. */
        public Builder sslContext(SSLContext value) {
            if (value == null) {
                throw new IllegalArgumentException("SSLContext is required");
            }
            context = value;
            return this;
        }

        public Builder handshakeTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("TLS handshake timeout must be positive");
            }
            handshakeTimeout = value;
            return this;
        }

        public Builder protocols(String... values) {
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("At least one TLS protocol is required");
            }
            for (String value : values) {
                if (!"TLSv1.2".equals(value) && !"TLSv1.3".equals(value)) {
                    throw new IllegalArgumentException("Only TLSv1.2 and TLSv1.3 are supported");
                }
            }
            protocols = values.clone();
            return this;
        }

        public BobaStrawTlsOptions build() {
            return new BobaStrawTlsOptions(this);
        }
    }
}
