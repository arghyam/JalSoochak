package org.arghyam.jalsoochak.scheme.statesync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.scheme.statesync.jjm.JjmBrainClient;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the state sync. The upstream client exists only when {@code state-sync.enabled=true}, so a
 * deployment with the flag off needs no API key and never opens a connection to the state system.
 */
@Configuration
@EnableConfigurationProperties(StateSyncProperties.class)
public class StateSyncConfig {

    @Bean
    @ConditionalOnProperty(prefix = "state-sync", name = "enabled", havingValue = "true")
    public StateMasterDataSource stateMasterDataSource(StateSyncProperties properties, ObjectMapper objectMapper) {
        return new JjmBrainClient(properties.getJjm(), objectMapper);
    }
}
