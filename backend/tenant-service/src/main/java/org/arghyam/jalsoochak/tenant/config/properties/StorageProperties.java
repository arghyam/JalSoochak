package org.arghyam.jalsoochak.tenant.config.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for object storage.
 * Bound from the {@code storage.*} namespace in application.yml.
 *
 * <p>{@code storage.endpoint} is required while storage is enabled, and is
 * reached with path-style access. There is no implicit AWS default.
 */
@ConfigurationProperties(prefix = "storage")
@Data
@Validated
public class StorageProperties {

    /**
     * Set to {@code true} to activate S3-compatible object storage.
     * When {@code false} (the default), the no-op fallback is used and file uploads are unavailable.
     */
    private boolean enabled = false;

    /** Storage provider key. Currently only {@code s3} is supported. */
    private String provider = "s3";

    /** URL of the S3-compatible store. Required while storage is enabled, or startup fails. */
    private String endpoint;

    /** Signing region (a placeholder like {@code us-east-1} for a store that ignores it). */
    @NotBlank
    private String region = "ap-south-1";

    /** Access key / access key ID. */
    private String accessKey;

    /** Secret key / secret access key. */
    private String secretKey;

    /** Bucket name for tenant assets. */
    @NotBlank
    private String bucket = "tenant-assets";

}
