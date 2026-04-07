package org.arghyam.jalsoochak.tenant;

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableConfigurationProperties(TenantDefaultsProperties.class)
public class TenantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantServiceApplication.class, args);
    }
}
