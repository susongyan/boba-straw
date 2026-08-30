package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Boot 2.7+/3.x auto-configuration without a Spring dependency in core. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BobaStrawProperties.class)
public class BobaStrawAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public BobaStrawClient bobaStrawClient(BobaStrawProperties properties) {
        return BobaStrawClient.builder().uri(properties.getUri())
            .commandTimeout(properties.getCommandTimeout())
            .protocol(properties.getProtocol()).build();
    }
}
