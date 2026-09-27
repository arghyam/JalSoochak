package org.arghyam.jalsoochak.tenant.config;

import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.tenant.config.properties.StorageProperties;
import org.arghyam.jalsoochak.tenant.exception.StorageException;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.arghyam.jalsoochak.tenant.storage.S3CompatibleStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.InputStream;
import java.net.URI;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    /**
     * S3Client bean — created only when {@code storage.access-key} is configured.
     * Talks to {@code storage.endpoint} with path-style access, which most S3-compatible stores other
     * than AWS require. The endpoint is required: there is no implicit AWS default, so a deployment
     * that misses it stops at startup instead of uploading to AWS.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        if (props.getEndpoint() == null || props.getEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.endpoint must be provided when storage.enabled=true");
        }
        if (props.getAccessKey() == null || props.getAccessKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.access-key is set but blank — provide a valid access key.");
        }
        if (props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.secret-key is set but blank — provide a valid secret key.");
        }
        log.info("[Storage] Using endpoint: {} (path-style enabled)", props.getEndpoint());
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .endpointOverride(URI.create(props.getEndpoint()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * S3-compatible storage service — activated when {@code storage.access-key} is set.
     * Covers AWS S3 and any other store that speaks the S3 API.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService s3CompatibleStorageService(S3Client s3Client, StorageProperties props) {
        log.info("[Storage] Activating S3-compatible storage service [bucket={}, endpoint={}]",
                props.getBucket(), props.getEndpoint());
        return new S3CompatibleStorageService(s3Client, props);
    }

    /**
     * No-op fallback — active when {@code storage.access-key} is absent (e.g. in tests
     * or when storage is intentionally not configured). Throws {@link StorageException}
     * at call time so the application starts cleanly but fails fast on actual use.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "false", matchIfMissing = true)
    public ObjectStorageService noOpObjectStorageService() {
        log.warn("[Storage] No storage credentials configured — file upload will be unavailable. " +
                "Set STORAGE_ACCESS_KEY to activate object storage.");
        return new ObjectStorageService() {
            @Override
            public String upload(String objectKey, InputStream content, long contentLength, String contentType) {
                throw new StorageException(
                        "Object storage is not configured. Set STORAGE_ACCESS_KEY to enable file uploads.");
            }

            @Override
            public void delete(String objectKey) {
                throw new StorageException("Object storage is not configured.");
            }

            @Override
            public InputStream download(String objectKey) {
                throw new StorageException("Object storage is not configured.");
            }
        };
    }
}
