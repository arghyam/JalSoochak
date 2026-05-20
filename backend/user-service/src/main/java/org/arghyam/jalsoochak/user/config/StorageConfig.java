package org.arghyam.jalsoochak.user.config;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.properties.StorageProperties;
import org.arghyam.jalsoochak.user.exceptions.StorageException;
import org.arghyam.jalsoochak.user.storage.ObjectStorageService;
import org.arghyam.jalsoochak.user.storage.S3CompatibleStorageService;
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

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    /**
     * S3Client bean — active when {@code storage.enabled=true}.
     * Path-style access is enabled when {@code storage.endpoint} is set
     * (required for MinIO and most non-AWS S3-compatible providers).
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

    /**
     * S3Presigner bean — always signs against the internal {@code storage.endpoint}.
     * MinIO validates the signature using the Host it receives from the reverse proxy
     * (configured via {@code proxy_set_header Host $proxy_host}), which matches the
     * internal host baked into the signature. The public-facing URL rewrite (origin
     * swap + path prefix) is applied post-sign in {@link S3CompatibleStorageService}.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Presigner s3Presigner(StorageProperties props) {
        validateCredentials(props);
        var builder = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()));

        if (hasCustomEndpoint(props)) {
            log.info("[Storage] Presigner using internal endpoint: {}", sanitizeEndpoint(props.getEndpoint()));
            builder.endpointOverride(URI.create(props.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService s3CompatibleStorageService(S3Client s3Client, S3Presigner s3Presigner,
                                                           StorageProperties props) {
        log.info("[Storage] Activating S3-compatible storage [bucket={}, reportsBucket={}, endpoint={}, presignedBaseUrl={}]",
                props.getBucket(), props.getReportsBucket(),
                props.getEndpoint() != null ? props.getEndpoint() : "AWS default",
                props.getPresignedBaseUrl() != null ? props.getPresignedBaseUrl() : "none (using endpoint)");
        return new S3CompatibleStorageService(s3Client, s3Presigner, props.getPresignedBaseUrl());
    }

    /**
     * No-op fallback — active when storage is not configured.
     * Application starts cleanly; any actual use fails fast with a clear message.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "false", matchIfMissing = true)
    public ObjectStorageService noOpObjectStorageService() {
        log.warn("[Storage] Object storage disabled (storage.enabled=false). " +
                "Set STORAGE_ENABLED=true to activate uploads / report generation.");
        return new ObjectStorageService() {
            @Override
            public String upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType) {
                throw new StorageException("Object storage is not configured.");
            }

            @Override
            public void delete(String bucket, String objectKey) {
                throw new StorageException("Object storage is not configured.");
            }

            @Override
            public InputStream download(String bucket, String objectKey) {
                throw new StorageException("Object storage is not configured.");
            }

            @Override
            public URI presignedGetUrl(String bucket, String objectKey, Duration ttl, String downloadFilename) {
                throw new StorageException("Object storage is not configured.");
            }
        };
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
