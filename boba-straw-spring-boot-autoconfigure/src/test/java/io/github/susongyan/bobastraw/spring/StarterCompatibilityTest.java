package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClusterClient;
import io.github.susongyan.bobastraw.BobaStrawSentinelClient;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in binding-to-wire checks against the same isolated C6/C7 fixtures as core tests. */
class StarterCompatibilityTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BobaStrawAutoConfiguration.class));

    @Test
    @EnabledIfSystemProperty(named = "boba.straw.runCluster", matches = "true")
    void clusterPropertiesCreateOnlyClusterBean() {
        runner.withPropertyValues("boba.straw.mode=cluster", "boba.straw.nodes[0]=127.0.0.1:17401",
            "boba.straw.nodes[1]=127.0.0.1:17402", "boba.straw.protocol=resp2").run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(BobaStrawClient.class);
                assertThat(context.getBean(BobaStrawClusterClient.class).sync().ping()).isEqualTo("PONG");
            });
    }

    @Test
    @EnabledIfSystemProperty(named = "boba.straw.runSentinel", matches = "true")
    void sentinelPropertiesCreateOnlySentinelBean() {
        runner.withPropertyValues("boba.straw.mode=sentinel", "boba.straw.master-name=tea",
            "boba.straw.password=boba-test-data", "boba.straw.sentinel-password=boba-test-sentinel",
            "boba.straw.nodes[0]=127.0.0.1:27501", "boba.straw.nodes[1]=127.0.0.1:27502")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(BobaStrawClient.class);
                assertThat(context.getBean(BobaStrawSentinelClient.class).sync().ping()).isEqualTo("PONG");
            });
    }

    @Test
    @EnabledIfSystemProperty(named = "boba.straw.runTls", matches = "true")
    void namedMixedTopologiesUseBoundTlsStoresAndAuthentication() {
        String directory = System.getenv("BOBA_TLS_CERT_DIR");
        assertThat(directory).isNotNull();
        String store = new File(directory, "client.p12").toURI().toString();
        ApplicationContextRunner configured = runner.withPropertyValues("boba.straw.default-client-enabled=false",
            "boba.straw.clients.cache.uri=rediss://127.0.0.1:17680",
            "boba.straw.clients.cache.password=boba-tls-test",
            "boba.straw.clients.cluster.mode=cluster", "boba.straw.clients.cluster.nodes[0]=127.0.0.1:17601",
            "boba.straw.clients.cluster.nodes[1]=127.0.0.1:17602",
            "boba.straw.clients.sentinel.mode=sentinel", "boba.straw.clients.sentinel.master-name=tea",
            "boba.straw.clients.sentinel.discovery-timeout=3s",
            "boba.straw.clients.sentinel.nodes[0]=127.0.0.1:27701");
        for (String name : new String[] {"cache", "cluster", "sentinel"}) {
            configured = configured.withPropertyValues("boba.straw.clients." + name + ".command-timeout=5s");
            configured = tls(configured, "boba.straw.clients." + name + ".tls", store);
        }
        configured = tls(configured, "boba.straw.clients.sentinel.sentinel-tls", store);
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            BobaStrawClients clients = context.getBean(BobaStrawClients.class);
            assertThat(clients.get("cache", BobaStrawClient.class).sync().ping()).isEqualTo("PONG");
            assertThat(clients.get("cluster", BobaStrawClusterClient.class).sync().ping()).isEqualTo("PONG");
            assertThat(clients.get("sentinel", BobaStrawSentinelClient.class).sync().ping()).isEqualTo("PONG");
        });
    }

    private ApplicationContextRunner tls(ApplicationContextRunner configured, String prefix, String store) {
        return configured.withPropertyValues(prefix + ".enabled=true", prefix + ".trust-store=" + store,
            prefix + ".trust-store-password=test-only", prefix + ".key-store=" + store,
            prefix + ".key-store-password=test-only");
    }
}
