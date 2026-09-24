package org.arghyam.jalsoochak.user.config;

import org.arghyam.jalsoochak.user.storage.ObjectStorageService;
import org.arghyam.jalsoochak.user.storage.S3CompatibleStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how object storage is wired: an enabled store needs an explicit {@code storage.endpoint}, with
 * no implicit AWS default, so a deployment that misses it stops at startup instead of talking to AWS.
 * A disabled store starts without one and falls back to the no-op store.
 */
@DisplayName("StorageConfig")
class StorageConfigTest {

    private static final String[] ENABLED_WITH_CREDENTIALS = {
            "storage.enabled=true",
            "storage.access-key=test-access",
            "storage.secret-key=test-secret"};
    private static final String ENDPOINT = "storage.endpoint=http://localhost:9000";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfig.class);

    @Test
    @DisplayName("enabled with an endpoint and credentials wires the S3-compatible adapter")
    void enabledWiresTheAdapter() {
        runner.withPropertyValues(ENABLED_WITH_CREDENTIALS)
                .withPropertyValues(ENDPOINT)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(S3Client.class)
                            .hasSingleBean(S3Presigner.class)
                            .hasSingleBean(ObjectStorageService.class);
                    assertThat(context.getBean(ObjectStorageService.class))
                            .isInstanceOf(S3CompatibleStorageService.class);
                });
    }

    @ParameterizedTest(name = "storage.endpoint={0}")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("enabled without an endpoint fails startup, naming the property")
    void missingEndpointFailsStartup(String endpoint) {
        ApplicationContextRunner withoutEndpoint = runner.withPropertyValues(ENABLED_WITH_CREDENTIALS);
        (endpoint == null ? withoutEndpoint : withoutEndpoint.withPropertyValues("storage.endpoint=" + endpoint))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("storage.endpoint"));
    }

    @Test
    @DisplayName("not enabled starts without an endpoint and falls back to the no-op store")
    void notEnabledNeedsNoEndpoint() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(ObjectStorageService.class)
                    .doesNotHaveBean(S3Client.class);
            assertThat(context.getBean(ObjectStorageService.class))
                    .isNotInstanceOf(S3CompatibleStorageService.class);
        });
    }
}
