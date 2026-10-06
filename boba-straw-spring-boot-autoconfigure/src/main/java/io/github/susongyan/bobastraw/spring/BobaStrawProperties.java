package io.github.susongyan.bobastraw.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration bound from {@code boba.straw}; legacy single-client keys remain valid. */
@ConfigurationProperties("boba.straw")
public class BobaStrawProperties extends BobaStrawClientProperties {
    private boolean enabled = true;
    private boolean defaultClientEnabled = true;
    private Map<String, BobaStrawClientProperties> clients =
        new LinkedHashMap<String, BobaStrawClientProperties>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isDefaultClientEnabled() {
        return defaultClientEnabled;
    }

    public void setDefaultClientEnabled(boolean value) {
        defaultClientEnabled = value;
    }

    public Map<String, BobaStrawClientProperties> getClients() {
        return clients;
    }

    public void setClients(Map<String, BobaStrawClientProperties> value) {
        clients = value;
    }
}
