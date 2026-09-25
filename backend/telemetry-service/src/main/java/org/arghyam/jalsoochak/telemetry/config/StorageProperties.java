package org.arghyam.jalsoochak.telemetry.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings, bound from {@code storage.*}.
 *
 * <p>{@code storage.endpoint} is required while storage is enabled, and is
 * reached with path-style access. There is no implicit AWS default.
 */
@ConfigurationProperties(prefix = "storage")
@Data
@Validated
public class StorageProperties {

    /** Set to {@code true} to activate object storage. */
    private boolean enabled = false;

    /** URL of the S3-compatible store. Required while storage is enabled, or startup fails. */
    private String endpoint;

    /** Signing region (a placeholder like {@code us-east-1} for a store that ignores it). */
    @NotBlank
    private String region = "us-east-1";

    /** Access key / access key ID. */
    private String accessKey;

    /** Secret key / secret access key. */
    private String secretKey;

    /**
     * Bucket holding inbound meter images. Required while storage is enabled, or startup fails. There is
     * no default, so a deployment that misses the variable stops rather than writing to the wrong bucket.
     */
    private String bucket;

    /**
     * Base URL of the anonymously readable address that
     * {@code ObjectStorageService.publicUrl} builds on:
     * {@code <publicBaseUrl>/<bucket>/<objectKey>}. Required while storage is enabled, as an
     * absolute {@code http(s)} URL with a host and no user-info, query or fragment, or startup fails.
     */
    private String publicBaseUrl;
}
