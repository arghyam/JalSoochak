package org.arghyam.jalsoochak.analytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shipped defaults of the KPI pre-aggregation switches. Dashboards must stay on the
 * live queries until an environment opts in, after its backfill has finished: reading the
 * aggregate tables before then serves figures from partly empty tables.
 */
class AggregationConfigDefaultsTest {

    @Test
    void aggregateReadsAndBackfillAreOffUnlessAnEnvironmentOptsIn() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();

        // Resolve placeholders with no env vars set, as a fresh environment would.
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new PropertiesPropertySource("application.yml", props));

        assertThat(env.getProperty("analytics.read-from-aggregates", Boolean.class)).isFalse();
        assertThat(env.getProperty("analytics.aggregation.backfill.enabled", Boolean.class)).isFalse();
    }
}
