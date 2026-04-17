package org.arghyam.jalsoochak.user.repository;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = startPostgres();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withInitScript("sql/test-schema.sql");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
