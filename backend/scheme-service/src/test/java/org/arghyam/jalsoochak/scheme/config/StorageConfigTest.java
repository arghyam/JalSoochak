package org.arghyam.jalsoochak.scheme.config;

import org.arghyam.jalsoochak.scheme.config.properties.StorageProperties;
import org.arghyam.jalsoochak.scheme.storage.ObjectStorageService;
import org.arghyam.jalsoochak.scheme.storage.S3CompatibleStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

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
                            .hasSingleBean(S3Presigner.class)
                            .hasSingleBean(ObjectStorageService.class);
                    assertThat(context.getBean(ObjectStorageService.class))
                            .isInstanceOf(S3CompatibleStorageService.class);
                });
    }

    @Test
    @DisplayName("storage.presigned-base-url replaces the origin of the signed URL")
    void presignedBaseUrlReachesTheAdapter() {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage.presigned-base-url=https://jalsoochak.in/storage")
                .run(context -> assertThat(context.getBean(ObjectStorageService.class)
                        .presignedGetUrl("jalsoochak-reports", "scheme/report.csv", Duration.ofMinutes(5), null)
                        .toString())
                        .startsWith("https://jalsoochak.in/storage/jalsoochak-reports/scheme/report.csv?")
                        .contains("X-Amz-Signature="));
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
                        .doesNotHaveBean(S3Client.class)
                        .doesNotHaveBean(S3Presigner.class));
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
            assertThat(props.getReportsBucket()).isEqualTo("jalsoochak-reports");
            assertThat(props.getRegion()).isEqualTo("us-east-1");
            assertThat(props.getPresignedTtlSeconds()).isEqualTo(3600L);
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
