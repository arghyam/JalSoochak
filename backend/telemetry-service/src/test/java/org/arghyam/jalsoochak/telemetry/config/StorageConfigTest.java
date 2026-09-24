package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.storage.ObjectStorageService;
import org.arghyam.jalsoochak.telemetry.storage.S3CompatibleStorageService;
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
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how object storage is wired: only on an explicit {@code storage.enabled=true}, which the shipped
 * {@code application.yml} sets, and with no no-op fallback, so a disabled store fails whatever needs it
 * at startup instead of letting every upload silently go nowhere. A public base URL the image URLs
 * cannot be built on fails startup too.
 */
@DisplayName("StorageConfig")
class StorageConfigTest {

    private static final String[] ENABLED_WITH_CREDENTIALS = {
            "storage.enabled=true",
            "storage.endpoint=http://localhost:9000",
            "storage.access-key=test-access",
            "storage.secret-key=test-secret"};
    private static final String PUBLIC_BASE_URL = "storage.public-base-url=https://storage.example.org";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfig.class);

    @Test
    @DisplayName("enabled with credentials wires the S3-compatible adapter")
    void enabledWiresTheAdapter() {
        enabled()
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
        enabled()
                .withPropertyValues("storage.public-base-url=https://jalsoochak.in/storage")
                .run(context -> assertThat(context.getBean(ObjectStorageService.class)
                        .publicUrl("jalsoochak", "k.bin"))
                        .hasToString("https://jalsoochak.in/storage/jalsoochak/k.bin"));
    }

    @ParameterizedTest(name = "storage.{0} blank")
    @ValueSource(strings = {"access-key", "secret-key"})
    @DisplayName("enabled with a blank credential fails startup, naming the property")
    void blankCredentialFailsStartup(String key) {
        enabled()
                .withPropertyValues("storage." + key + "=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage." + key));
    }

    @ParameterizedTest(name = "storage.public-base-url={0}")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("enabled without a public base URL fails startup, naming the property")
    void missingPublicBaseUrlFailsStartup(String publicBaseUrl) {
        ApplicationContextRunner withoutBaseUrl = runner.withPropertyValues(ENABLED_WITH_CREDENTIALS);
        (publicBaseUrl == null ? withoutBaseUrl
                : withoutBaseUrl.withPropertyValues("storage.public-base-url=" + publicBaseUrl))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage.public-base-url"));
    }

    @ParameterizedTest(name = "storage.public-base-url={0}")
    @ValueSource(strings = {
            "storage.example.org/objects",
            "ftp://storage.example.org/objects",
            "https://",
            "https://storage example.org/objects",
            "https://reader:s3cr3t@storage.example.org/objects",
            "https://storage.example.org/objects?region=south",
            "https://storage.example.org/objects#top"})
    @DisplayName("an unusable public base URL fails startup, naming the property but not the value")
    void unusablePublicBaseUrlFailsStartup(String publicBaseUrl) {
        enabled()
                .withPropertyValues("storage.public-base-url=" + publicBaseUrl)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage.public-base-url")
                        .hasMessageNotContaining(publicBaseUrl));
    }

    @ParameterizedTest(name = "storage.public-base-url={0}")
    @ValueSource(strings = {"http://localhost:9000", "HTTPS://storage.example.org/objects/"})
    @DisplayName("an absolute http(s) public base URL starts")
    void usablePublicBaseUrlStarts(String publicBaseUrl) {
        enabled()
                .withPropertyValues("storage.public-base-url=" + publicBaseUrl)
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
            assertThat(props.getBucket()).isEqualTo("jalsoochak");
            assertThat(props.getRegion()).isEqualTo("us-east-1");
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
        assertThat(props.getBucket()).isEqualTo("jalsoochak");
        assertThat(props.getRegion()).isEqualTo("us-east-1");
        assertThat(props.getEndpoint()).isEmpty();
        assertThat(props.getPublicBaseUrl()).as("blank, so an unset base URL fails startup").isEmpty();
        assertThat(props.getAccessKey()).as("blank, so an unset credential fails startup").isEmpty();
        assertThat(props.getSecretKey()).as("blank, so an unset credential fails startup").isEmpty();
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(ENABLED_WITH_CREDENTIALS).withPropertyValues(PUBLIC_BASE_URL);
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
