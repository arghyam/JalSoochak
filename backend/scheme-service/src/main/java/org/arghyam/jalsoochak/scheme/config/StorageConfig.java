package org.arghyam.jalsoochak.scheme.config;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.config.properties.StorageProperties;
import org.arghyam.jalsoochak.scheme.storage.ObjectStorageService;
import org.arghyam.jalsoochak.scheme.storage.S3CompatibleStorageService;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

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
                .credentialsProvider(credentials(props))
                .region(Region.of(props.getRegion()));

        if (hasCustomEndpoint(props)) {
            log.info("[Storage] Using custom endpoint: {} (path-style enabled)", sanitizeEndpoint(props.getEndpoint()));
            builder.endpointOverride(URI.create(props.getEndpoint()))
                    .serviceConfiguration(pathStyle());
        }
        return builder.build();
    }

    /**
     * Always signs against the internal {@code storage.endpoint}. The store validates the
     * signature using the Host it receives from the reverse proxy (configured via
     * {@code proxy_set_header Host $proxy_host}), which matches the internal host baked into
     * the signature. The public-facing URL rewrite is applied post-sign in
     * {@link S3CompatibleStorageService}.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Presigner s3Presigner(StorageProperties props) {
        validateCredentials(props);
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentials(props))
                .region(Region.of(props.getRegion()));

        if (hasCustomEndpoint(props)) {
            builder.endpointOverride(URI.create(props.getEndpoint()))
                    .serviceConfiguration(pathStyle());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService objectStorageService(S3Client s3Client, S3Presigner s3Presigner,
                                                     StorageProperties props) {
        log.info("[Storage] Activating S3-compatible storage [reportsBucket={}, endpoint={}, presignedBaseUrl={}]",
                props.getReportsBucket(),
                hasCustomEndpoint(props) ? sanitizeEndpoint(props.getEndpoint()) : "AWS default",
                props.getPresignedBaseUrl() != null ? props.getPresignedBaseUrl() : "none (using endpoint)");
        return new S3CompatibleStorageService(s3Client, s3Presigner, props.getPresignedBaseUrl());
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

    private static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    private static S3Configuration pathStyle() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
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
