package io.github.susongyan.bobastraw;

import java.time.Duration;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TlsOptionsTest {
    @Test
    void defaultsKeepIdentityVerificationAndOnlyModernProtocols() {
        BobaStrawTlsOptions options = BobaStrawTlsOptions.defaults();
        SSLEngine engine = options.createEngine("cache.example", 6379);
        assertTrue(engine.getUseClientMode());
        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm());
        assertEquals(Duration.ofSeconds(5), options.handshakeTimeout());
        assertTrue(engine.getEnabledProtocols().length > 0);
        for (String protocol : engine.getEnabledProtocols()) {
            assertTrue("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol));
        }
    }

    @Test
    void builderRejectsInsecureVersionsAndSnapshotsProtocolArrays() {
        String[] versions = {"TLSv1.2"};
        BobaStrawTlsOptions options = BobaStrawTlsOptions.builder().protocols(versions).build();
        versions[0] = "TLSv1";
        assertArrayEquals(new String[] {"TLSv1.2"}, options.createEngine("localhost", 6379)
            .getEnabledProtocols());
        assertThrows(IllegalArgumentException.class, () -> BobaStrawTlsOptions.builder().protocols("TLSv1"));
        assertThrows(IllegalArgumentException.class, () -> BobaStrawTlsOptions.builder().protocols());
        assertThrows(IllegalArgumentException.class,
            () -> BobaStrawTlsOptions.builder().handshakeTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BobaStrawTlsOptions.builder().sslContext(null));
    }
}
