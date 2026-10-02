package org.arghyam.jalsoochak.tenant.config;

import org.springframework.context.annotation.Configuration;

/**
 * DataSource configuration.
 * <p>
 * Spring Boot auto-configures the {@link javax.sql.DataSource} and
 * {@link org.springframework.jdbc.core.JdbcTemplate} from {@code application.yml}.
 * <p>
 * <strong>To connect to your database</strong>, update the following properties
 * in {@code application.yml}:
 * <ul>
 *   <li>{@code spring.datasource.url} – JDBC URL (e.g.
 *       {@code jdbc:postgresql://localhost:5432/shared_db?stringtype=unspecified})</li>
 *   <li>{@code spring.datasource.username}</li>
 *   <li>{@code spring.datasource.password}</li>
 * </ul>
 * <p>
 * Multi-tenancy is handled at the query level: common-schema queries use the
 * explicit {@code common_schema.} prefix, while tenant-schema queries use the
 * schema name resolved via {@link TenantContext}.
 */
@Configuration
public class DataSourceConfig {
    // DataSource, JdbcTemplate, and TransactionManager are auto-configured
    // via spring.datasource.* properties in application.yml.
    //
    // ThreadPoolTaskScheduler was removed when per-tenant cron scheduling moved
    // to K8s CronJobs (see JobTriggerController + build/k8s/cronjobs/).
}

