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
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how object storage is wired: only on an explicit {@code storage.enabled=true}, which the shipped
 * {@code application.yml} sets, and with no no-op fallback, so a disabled store fails whatever needs it
 * at startup instead of letting every upload silently go nowhere. Settings that would only break the
 * download links fail startup too.
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
    @ValueSource(strings = {"endpoint", "access-key", "secret-key"})
    @DisplayName("enabled with a blank endpoint or credential fails startup, naming the property")
    void blankEndpointOrCredentialFailsStartup(String key) {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage." + key + "=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage." + key));
    }

    @ParameterizedTest(name = "storage.presigned-ttl-seconds={0}")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("a non-positive presigned TTL fails startup")
    void nonPositivePresignedTtlFailsStartup(String ttlSeconds) {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage.presigned-ttl-seconds=" + ttlSeconds)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(BindValidationException.class)
                        .hasMessageContaining("presignedTtlSeconds"));
    }

    @ParameterizedTest(name = "storage.presigned-base-url={0}")
    @ValueSource(strings = {
            "reports.example.org/storage",
            "ftp://reports.example.org/storage",
            "https://",
            "https://reports example.org/storage",
            "https://reader:s3cr3t@reports.example.org/storage",
            "https://reports.example.org/storage?region=south",
            "https://reports.example.org/storage#top"})
    @DisplayName("a presigned base URL the rewrite cannot use fails startup, naming the property but not the value")
    void unusablePresignedBaseUrlFailsStartup(String presignedBaseUrl) {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage.presigned-base-url=" + presignedBaseUrl)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage.presigned-base-url")
                        .hasMessageNotContaining(presignedBaseUrl));
    }

    @ParameterizedTest(name = "storage.presigned-base-url={0}")
    @ValueSource(strings = {"", "http://localhost:9443", "HTTPS://reports.example.org/storage/"})
    @DisplayName("a blank or absolute http(s) presigned base URL starts")
    void usablePresignedBaseUrlStarts(String presignedBaseUrl) {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues("storage.presigned-base-url=" + presignedBaseUrl)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageService.class));
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

    /**
     * Binds the shipped {@code application.yml} with nothing else resolving its placeholders, so the
     * result is what a deployment gets when it sets none of the {@code STORAGE_*} variables. Fails on
     * a {@code storage.*} key that {@link StorageProperties} has no field for.
     */
    @Test
    @DisplayName("the shipped application.yml enables storage when STORAGE_ENABLED is unset, with these defaults")
    void shippedYamlEnablesStorageByDefault() throws IOException {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(yaml),
                new PropertySourcesPlaceholdersResolver(yaml));

        StorageProperties props = binder.bind("storage", Bindable.of(StorageProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getReportsBucket()).isEqualTo("jalsoochak-reports");
        assertThat(props.getRegion()).isEqualTo("us-east-1");
        assertThat(props.getPresignedTtlSeconds()).isEqualTo(3600L);
        assertThat(props.getEndpoint()).as("blank, so an unset endpoint fails startup").isEmpty();
        assertThat(props.getPresignedBaseUrl()).isEmpty();
        assertThat(props.getAccessKey()).as("blank, so an unset credential fails startup").isEmpty();
        assertThat(props.getSecretKey()).as("blank, so an unset credential fails startup").isEmpty();
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
