package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.storage.ObjectStorageService;
import org.arghyam.jalsoochak.message.storage.S3CompatibleStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how object storage is wired: only on an explicit {@code storage.enabled=true}, and with no
 * no-op fallback, so a disabled store fails whatever needs it at startup instead of letting every
 * upload silently go nowhere.
 */
@DisplayName("StorageConfig")
class StorageConfigTest {

    private static final String[] ENABLED_WITH_CREDENTIALS = {
            "storage.enabled=true",
            "storage.endpoint=http://localhost:9000",
            "storage.access-key=test-access",
            "storage.secret-key=test-secret"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfig.class);

    @Test
    @DisplayName("enabled with credentials wires the S3-compatible adapter")
    void enabledWiresTheAdapter() {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(S3Client.class)
                            .hasSingleBean(ObjectStorageService.class);
                    assertThat(context.getBean(ObjectStorageService.class))
                            .isInstanceOf(S3CompatibleStorageService.class);
                });
    }

    @Test
    @DisplayName("storage.public-base-url reaches the adapter's publicUrl")
    void publicBaseUrlReachesTheAdapter() {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage.public-base-url=https://jalsoochak.in/storage")
                .run(context -> assertThat(context.getBean(ObjectStorageService.class)
                        .publicUrl("escalation-reports", "k.bin"))
                        .hasToString("https://jalsoochak.in/storage/escalation-reports/k.bin"));
    }

    @ParameterizedTest(name = "storage.{0} blank")
    @ValueSource(strings = {"access-key", "secret-key"})
    @DisplayName("enabled with a blank credential fails startup, naming the property")
    void blankCredentialFailsStartup(String key) {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage." + key + "=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage." + key));
    }

    @ParameterizedTest(name = "storage.enabled={0}")
    @NullSource
    @ValueSource(strings = "false")
    @DisplayName("not enabled registers no storage bean, and no no-op stands in for one")
    void notEnabledRegistersNoStorageBean(String enabled) {
        withEnabled(enabled)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ObjectStorageService.class)
                        .doesNotHaveBean(S3Client.class));
    }

    @ParameterizedTest(name = "storage.enabled={0}")
    @NullSource
    @ValueSource(strings = "false")
    @DisplayName("not enabled fails startup of anything that needs storage")
    void notEnabledFailsStartupOfAStorageConsumer(String enabled) {
        withEnabled(enabled)
                .withBean(StorageConsumer.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining(ObjectStorageService.class.getName()));
    }

    @Test
    @DisplayName("properties bind and validate while storage is dormant, with this service's defaults")
    void dormantPropertiesCarryThisServicesDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(StorageProperties.class);
            StorageProperties props = context.getBean(StorageProperties.class);
            assertThat(props.getBucket()).isEqualTo("escalation-reports");
            assertThat(props.getRegion()).isEqualTo("us-east-1");
        });
    }

    private ApplicationContextRunner withEnabled(String enabled) {
        return enabled == null ? runner : runner.withPropertyValues("storage.enabled=" + enabled);
    }

    /** Stands in for a caller of the port. */
    static class StorageConsumer {
        StorageConsumer(ObjectStorageService storage) {
        }
    }
}
