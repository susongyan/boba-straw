package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.ProtocolVersion;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration bound from {@code boba.straw}. */
@ConfigurationProperties("boba.straw")
public class BobaStrawProperties {
    private String uri = "redis://localhost:6379";
    private Duration commandTimeout = Duration.ofSeconds(2);
    private ProtocolVersion protocol = ProtocolVersion.AUTO;
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public Duration getCommandTimeout() { return commandTimeout; }
    public void setCommandTimeout(Duration commandTimeout) { this.commandTimeout = commandTimeout; }
    public ProtocolVersion getProtocol() { return protocol; }
    public void setProtocol(ProtocolVersion protocol) { this.protocol = protocol; }
}
