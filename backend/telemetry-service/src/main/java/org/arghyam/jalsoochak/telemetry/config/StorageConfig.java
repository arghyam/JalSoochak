package org.arghyam.jalsoochak.telemetry.config;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.storage.ObjectStorageService;
import org.arghyam.jalsoochak.telemetry.storage.S3CompatibleStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Wires the S3-compatible {@link ObjectStorageService} when {@code storage.enabled=true}.
 *
 * <p>There is deliberately no no-op fallback for a disabled store: this service has no degraded
 * mode for storage, so anything that needs {@link ObjectStorageService} fails at startup rather
 * than every upload silently going nowhere.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    /**
     * Path-style access is enabled when {@code storage.endpoint} is set, as most non-AWS
     * S3-compatible stores require.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        validateCredentials(props);
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()));

        if (hasCustomEndpoint(props)) {
            log.info("[Storage] Using custom endpoint: {} (path-style enabled)", sanitizeEndpoint(props.getEndpoint()));
            builder.endpointOverride(URI.create(props.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService objectStorageService(S3Client s3Client, StorageProperties props) {
        validatePublicBaseUrl(props.getPublicBaseUrl());
        log.info("[Storage] Activating S3-compatible storage [bucket={}, endpoint={}, publicBaseUrl={}]",
                props.getBucket(),
                hasCustomEndpoint(props) ? sanitizeEndpoint(props.getEndpoint()) : "AWS default",
                props.getPublicBaseUrl());
        return new S3CompatibleStorageService(s3Client, props.getPublicBaseUrl());
    }

    private static void validateCredentials(StorageProperties props) {
        if (props.getAccessKey() == null || props.getAccessKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.access-key must be provided when storage.enabled=true");
        }
        if (props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.secret-key must be provided when storage.enabled=true");
        }
    }

    /**
     * Every stored image URL is built on this base and fetched by the OCR provider, so a missing or
     * malformed value would fail each upload after the object is already stored. Checked here instead,
     * so the deployment stops rather than the readings.
     */
    private static void validatePublicBaseUrl(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.public-base-url must be provided when storage.enabled=true");
        }
        URI base;
        try {
            base = new URI(publicBaseUrl.strip());
        } catch (URISyntaxException e) {
            throw invalidPublicBaseUrl();
        }
        boolean httpScheme = "http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme());
        if (!httpScheme || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw invalidPublicBaseUrl();
        }
    }

    /** Does not echo the value, which may carry credentials in its user-info. */
    private static IllegalStateException invalidPublicBaseUrl() {
        return new IllegalStateException("[Storage] storage.public-base-url must be an absolute http(s) URL "
                + "with a host and no user-info, query or fragment");
    }

    private static boolean hasCustomEndpoint(StorageProperties props) {
        return props.getEndpoint() != null && !props.getEndpoint().isBlank();
    }

    private static String sanitizeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            String base = uri.getScheme() + "://" + (host != null ? host : "");
            return port > 0 ? base + ":" + port : base;
        } catch (Exception e) {
            return "<unparseable-endpoint>";
        }
    }
}
