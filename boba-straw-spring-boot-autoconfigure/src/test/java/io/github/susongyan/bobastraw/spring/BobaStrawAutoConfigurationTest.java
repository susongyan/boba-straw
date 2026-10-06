package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawConnectionState;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BobaStrawAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BobaStrawAutoConfiguration.class));

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class Discovery {
    }

    @Test
    void bootDiscoversTheStarterAndBindsMetersAutomatically() throws Exception {
        try (StarterTestServer server = new StarterTestServer()) {
            new ApplicationContextRunner().withUserConfiguration(Discovery.class)
                .withPropertyValues("boba.straw.uri=" + server.uri()).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BobaStrawClient.class);
                    context.getBean(BobaStrawClient.class).sync().ping();
                    io.micrometer.core.instrument.MeterRegistry registry =
                        context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                    assertThat(registry.get("boba.straw.ready").gauge().value()).isEqualTo(1);
                });
        }
    }

    @Test
    void actuatorWithoutItsAutoConfigurationRemainsOptional() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate.autoconfigure"))
            .withPropertyValues("boba.straw.default-client-enabled=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("bobaStrawHealthIndicator"));
    }

    @Test
    void globalHealthDisableIsRespected() {
        runner.withPropertyValues("boba.straw.default-client-enabled=false", "management.health.defaults.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("bobaStrawHealthIndicator"));
    }

    @Test
    void metricCollisionRollsBackOnlyItsOwnRegistrations() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (StarterTestServer server = new StarterTestServer()) {
            io.micrometer.core.instrument.Gauge external = io.micrometer.core.instrument.Gauge
                .builder("boba.straw.inflight", () -> 7).tag("client", "bean:bobaStrawClient").register(registry);
            runner.withPropertyValues("boba.straw.uri=" + server.uri()).run(context -> {
                BobaStrawMetrics metrics = context.getBean(BobaStrawMetrics.class);
                assertThatThrownBy(() -> metrics.bindTo(registry)).isInstanceOf(IllegalStateException.class);
                assertThat(registry.getMeters()).containsExactly(external);
            });
            assertThat(registry.getMeters()).containsExactly(external);
        } finally {
            registry.close();
        }
    }

    @Test
    void legacyFactoryRejectsOtherTopologiesBeforeAllocatingResources() {
        BobaStrawProperties properties = new BobaStrawProperties();
        properties.setMode(BobaStrawClientProperties.Mode.CLUSTER);
        assertThatThrownBy(() -> new BobaStrawAutoConfiguration().bobaStrawClient(properties))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyPropertiesBindAndContextOwnsDefaultClient() throws Exception {
        AtomicReference<BobaStrawClient> captured = new AtomicReference<BobaStrawClient>();
        try (StarterTestServer server = new StarterTestServer()) {
            runner.withPropertyValues("boba.straw.uri=" + server.uri(), "boba.straw.protocol=resp2",
                "boba.straw.command-timeout=750ms").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BobaStrawClient.class);
                    assertThat(context.getBean(BobaStrawProperties.class).getCommandTimeout())
                        .isEqualTo(Duration.ofMillis(750));
                    assertThat(context.getBean(BobaStrawProperties.class).getProtocol())
                        .isEqualTo(ProtocolVersion.RESP2);
                    BobaStrawClient client = context.getBean(BobaStrawClient.class);
                    captured.set(client);
                    assertThat(client.sync().ping()).isEqualTo("PONG");
                });
            assertThat(captured.get().metrics().sharedConnectionState()).isEqualTo(BobaStrawConnectionState.CLOSED);
        }
    }

    @Test
    void namedClientsAreIndependentAndClosedWithTheirContext() throws Exception {
        AtomicReference<BobaStrawClients> captured = new AtomicReference<BobaStrawClients>();
        try (StarterTestServer first = new StarterTestServer(); StarterTestServer second = new StarterTestServer()) {
            runner.withPropertyValues("boba.straw.default-client-enabled=false",
                "boba.straw.clients.cache.uri=" + first.uri(),
                "boba.straw.clients.jobs.uri=" + second.uri()).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BobaStrawClient.class);
                    BobaStrawClients clients = context.getBean(BobaStrawClients.class);
                    captured.set(clients);
                    assertThat(clients.get("cache", BobaStrawClient.class).sync().ping()).isEqualTo("PONG");
                    assertThat(clients.get("jobs", BobaStrawClient.class).sync().ping()).isEqualTo("PONG");
                    assertThatThrownBy(() -> clients.get("missing", BobaStrawClient.class))
                        .isInstanceOf(IllegalArgumentException.class);
                });
            for (String name : new String[] {"cache", "jobs"}) {
                assertThat(captured.get().get(name, BobaStrawClient.class).metrics().sharedConnectionState())
                    .isEqualTo(BobaStrawConnectionState.CLOSED);
            }
            captured.get().close();
        }
    }

    @Test
    void localHealthAndMetersDoNotIssueCommandsAndMetersAreRemoved() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (StarterTestServer server = new StarterTestServer()) {
            runner.withPropertyValues("boba.straw.uri=" + server.uri()).run(context -> {
                BobaStrawClient client = context.getBean(BobaStrawClient.class);
                client.sync().ping();
                int commands = server.commands.get();
                HealthIndicator health = context.getBean("bobaStrawHealthIndicator", HealthIndicator.class);
                assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                BobaStrawMetrics metrics = context.getBean(BobaStrawMetrics.class);
                metrics.bindTo(registry);
                metrics.bindTo(registry);
                assertThat(registry.getMeters()).hasSize(3);
                assertThat(registry.get("boba.straw.ready").gauge().value()).isEqualTo(1);
                assertThat(server.commands.get()).isEqualTo(commands);
                client.close();
                assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
                assertThat(registry.get("boba.straw.ready").gauge().value()).isZero();
            });
            assertThat(registry.getMeters()).isEmpty();
        } finally {
            registry.close();
        }
    }

    @Test
    void missingOptionalLibrariesDoNotPreventStartup() throws Exception {
        try (StarterTestServer server = new StarterTestServer()) {
            runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate", "io.micrometer"))
                .withPropertyValues("boba.straw.uri=" + server.uri()).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BobaStrawClient.class)
                        .doesNotHaveBean("bobaStrawHealthIndicator").doesNotHaveBean("bobaStrawMetrics");
                    assertThat(context.getBean(BobaStrawClient.class).sync().ping()).isEqualTo("PONG");
                });
        }
    }

    @Test
    void disabledConfigurationCreatesNoClients() {
        runner.withPropertyValues("boba.straw.enabled=false").run(context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(BobaStrawClient.class)
                .doesNotHaveBean(BobaStrawClients.class).doesNotHaveBean(BobaStrawMetrics.class));
    }

    @Test
    void userClientWinsAndMonitoringCanBeDisabled() throws Exception {
        try (StarterTestServer server = new StarterTestServer();
             BobaStrawClient custom = BobaStrawClient.builder().uri(server.uri()).build()) {
            runner.withBean("custom", BobaStrawClient.class, () -> custom)
                .withPropertyValues("management.health.boba-straw.enabled=false", "boba.straw.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BobaStrawClient.class)
                        .doesNotHaveBean("bobaStrawClient").doesNotHaveBean("bobaStrawHealthIndicator")
                        .doesNotHaveBean(BobaStrawMetrics.class);
                    assertThat(context.getBean(BobaStrawClient.class)).isSameAs(custom);
                });
        }
    }

    @Test
    void invalidTopologyAndTlsConfigurationFailClosed() {
        for (String[] properties : new String[][] {
            {"boba.straw.mode=cluster"},
            {"boba.straw.mode=sentinel", "boba.straw.nodes[0]=127.0.0.1:1"},
            {"boba.straw.tls.trust-store=file:/missing"},
            {"boba.straw.tls.enabled=true", "boba.straw.tls.trust-store=file:/missing"},
            {"boba.straw.command-timeout=0s"},
            {"boba.straw.mode=bad-mode"}
        }) {
            runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void partialNamedCreationFailureClosesPreviouslyCreatedSockets() throws Exception {
        try (StarterTestServer server = new StarterTestServer()) {
            runner.withPropertyValues("boba.straw.default-client-enabled=false",
                "boba.straw.clients.first.uri=" + server.uri(),
                "boba.straw.clients.second.tls.enabled=true",
                "boba.straw.clients.second.tls.trust-store=file:/missing")
                .run(context -> assertThat(context).hasFailed());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (server.active.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(server.active.get()).isZero();
        }
    }
}
