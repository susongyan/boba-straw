package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClusterClient;
import io.github.susongyan.bobastraw.BobaStrawSentinelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

/** Spring Boot 2.7+/3.x auto-configuration without a Spring dependency in core. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BobaStrawProperties.class)
@ConditionalOnProperty(prefix = "boba.straw", name = "enabled", matchIfMissing = true)
@Import({BobaStrawAutoConfiguration.DefaultClients.class, BobaStrawMonitoringConfiguration.class})
public class BobaStrawAutoConfiguration {
    /** Compatibility entry point; Spring uses the conditional topology-specific bean methods. */
    @Deprecated
    public BobaStrawClient bobaStrawClient(BobaStrawProperties properties) {
        if (properties.getMode() != BobaStrawClientProperties.Mode.STANDALONE) {
            throw new IllegalArgumentException("The legacy factory only accepts Standalone mode");
        }
        return (BobaStrawClient) new BobaStrawClientFactory(
            new org.springframework.core.io.DefaultResourceLoader()).create(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public BobaStrawClients bobaStrawClients(BobaStrawProperties properties, ResourceLoader resources) {
        return new BobaStrawClients(properties.getClients(), resources);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "boba.straw", name = "default-client-enabled", matchIfMissing = true)
    @ConditionalOnMissingBean({BobaStrawClient.class, BobaStrawClusterClient.class,
        BobaStrawSentinelClient.class})
    static class DefaultClients {
        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "boba.straw", name = "mode", havingValue = "standalone",
            matchIfMissing = true)
        BobaStrawClient bobaStrawClient(BobaStrawProperties properties, ResourceLoader resources) {
            return (BobaStrawClient) new BobaStrawClientFactory(resources).create(properties);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "boba.straw", name = "mode", havingValue = "cluster")
        BobaStrawClusterClient bobaStrawClusterClient(BobaStrawProperties properties,
            ResourceLoader resources) {
            return (BobaStrawClusterClient) new BobaStrawClientFactory(resources).create(properties);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "boba.straw", name = "mode", havingValue = "sentinel")
        BobaStrawSentinelClient bobaStrawSentinelClient(BobaStrawProperties properties,
            ResourceLoader resources) {
            return (BobaStrawSentinelClient) new BobaStrawClientFactory(resources).create(properties);
        }
    }
}
