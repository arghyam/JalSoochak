package org.arghyam.jalsoochak.message.config;

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

    /** Bucket for escalation reports. The daily and weekly reports name their own buckets. */
    @NotBlank
    private String bucket = "escalation-reports";

    /**
     * Base URL of the anonymously readable address that
     * {@code ObjectStorageService.publicUrl} builds on:
     * {@code <publicBaseUrl>/<bucket>/<objectKey>}. Required while storage is enabled, as an
     * absolute {@code http(s)} URL with a host and no user-info, query or fragment, or startup fails.
     * Meta downloads report URLs built on it from its own network, so it must be public.
     */
    private String publicBaseUrl;
}
