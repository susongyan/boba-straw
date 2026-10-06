package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClusterClient;
import io.github.susongyan.bobastraw.BobaStrawSentinelClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional dependencies are isolated behind separate conditional configuration classes. */
@Configuration(proxyBeanMethods = false)
class BobaStrawMonitoringConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.boot.actuate.health.HealthIndicator",
        "org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator"})
    static class HealthConfiguration {
        @Bean
        @org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator("boba-straw")
        @ConditionalOnMissingBean(name = "bobaStrawHealthIndicator")
        org.springframework.boot.actuate.health.HealthIndicator bobaStrawHealthIndicator(
            Map<String, BobaStrawClient> standalone, Map<String, BobaStrawClusterClient> cluster,
            Map<String, BobaStrawSentinelClient> sentinel, BobaStrawClients named) {
            List<BobaStrawObservation> observations = BobaStrawObservation.all(standalone, cluster, sentinel, named);
            return () -> {
                boolean ready = !observations.isEmpty();
                Map<String, String> states = new LinkedHashMap<String, String>();
                for (BobaStrawObservation observation : observations) {
                    boolean current = observation.ready();
                    ready &= current;
                    states.put(observation.name, current ? "READY" : "NOT_READY");
                }
                return (ready ? org.springframework.boot.actuate.health.Health.up()
                    : org.springframework.boot.actuate.health.Health.unknown())
                    .status(observations.isEmpty() ? "UNKNOWN" : ready ? "UP" : "DOWN")
                    .withDetail("probe", "local-connection-state").withDetail("clients", states).build();
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    @ConditionalOnProperty(prefix = "boba.straw.metrics", name = "enabled", matchIfMissing = true)
    static class MetricsConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(BobaStrawMetrics.class)
        BobaStrawMetrics bobaStrawMetrics(Map<String, BobaStrawClient> standalone,
            Map<String, BobaStrawClusterClient> cluster, Map<String, BobaStrawSentinelClient> sentinel,
            BobaStrawClients named) {
            return new BobaStrawMetrics(BobaStrawObservation.all(standalone, cluster, sentinel, named));
        }
    }
}
