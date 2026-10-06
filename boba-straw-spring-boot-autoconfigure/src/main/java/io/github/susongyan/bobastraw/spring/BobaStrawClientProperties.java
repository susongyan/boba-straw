package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.ProtocolVersion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A standalone, Cluster or Sentinel client. Credentials must not be logged. */
public class BobaStrawClientProperties {
    public enum Mode {
        STANDALONE, CLUSTER, SENTINEL
}
    private String uri = "redis://localhost:6379";
    private Mode mode = Mode.STANDALONE;
    private Duration commandTimeout = Duration.ofSeconds(2);
    private ProtocolVersion protocol = ProtocolVersion.AUTO;
    private List<String> nodes = new ArrayList<String>();
    private String masterName = null;
    private String username = null;
    private String password = null;
    private String sentinelUsername = null;
    private String sentinelPassword = null;
    private Duration topologyRefreshInterval;
    private Duration discoveryTimeout = Duration.ofMillis(500);
    private Tls tls = new Tls();
    private Tls sentinelTls = new Tls();

    public String getUri() {
        return uri;
    }

    public void setUri(String value) {
        uri = value;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode value) {
        mode = value;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration value) {
        commandTimeout = value;
    }

    public ProtocolVersion getProtocol() {
        return protocol;
    }

    public void setProtocol(ProtocolVersion value) {
        protocol = value;
    }

    public List<String> getNodes() {
        return nodes;
    }

    public void setNodes(List<String> value) {
        nodes = value;
    }

    public String getMasterName() {
        return masterName;
    }

    public void setMasterName(String value) {
        masterName = value;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        username = value;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value;
    }

    public String getSentinelUsername() {
        return sentinelUsername;
    }

    public void setSentinelUsername(String value) {
        sentinelUsername = value;
    }

    public String getSentinelPassword() {
        return sentinelPassword;
    }

    public void setSentinelPassword(String value) {
        sentinelPassword = value;
    }

    public Duration getTopologyRefreshInterval() {
        return topologyRefreshInterval;
    }

    public void setTopologyRefreshInterval(Duration value) {
        topologyRefreshInterval = value;
    }

    public Duration getDiscoveryTimeout() {
        return discoveryTimeout;
    }

    public void setDiscoveryTimeout(Duration value) {
        discoveryTimeout = value;
    }

    public Tls getTls() {
        return tls;
    }

    public void setTls(Tls value) {
        tls = value;
    }

    public Tls getSentinelTls() {
        return sentinelTls;
    }

    public void setSentinelTls(Tls value) {
        sentinelTls = value;
    }

    /** Optional JDK trust/key stores. Endpoint verification cannot be disabled. */
    public static class Tls {
        private boolean enabled;
        private String trustStore = null;
        private String trustStorePassword = null;
        private String keyStore = null;
        private String keyStorePassword = null;
        private String storeType = "PKCS12";
        private Duration handshakeTimeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public String getTrustStore() {
            return trustStore;
        }

        public void setTrustStore(String value) {
            trustStore = value;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public void setTrustStorePassword(String value) {
            trustStorePassword = value;
        }

        public String getKeyStore() {
            return keyStore;
        }

        public void setKeyStore(String value) {
            keyStore = value;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String value) {
            keyStorePassword = value;
        }

        public String getStoreType() {
            return storeType;
        }

        public void setStoreType(String value) {
            storeType = value;
        }

        public Duration getHandshakeTimeout() {
            return handshakeTimeout;
        }

        public void setHandshakeTimeout(Duration value) {
            handshakeTimeout = value;
        }

    }
}
