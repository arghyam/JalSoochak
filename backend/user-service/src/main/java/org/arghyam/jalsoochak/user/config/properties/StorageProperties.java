package org.arghyam.jalsoochak.user.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for object storage.
 * Bound from the {@code storage.*} namespace in application.yml.
 *
 * <p>Setting {@code storage.endpoint} to a non-blank URL activates path-style
 * access and endpoint override, required by most non-AWS S3-compatible
 * stores. Leave it blank to use real AWS S3.
 */
@ConfigurationProperties(prefix = "storage")
@Data
@Validated
public class StorageProperties {

    /** Set to {@code true} to activate S3-compatible object storage. */
    private boolean enabled = false;

    /** Storage provider key. Currently only {@code s3} is supported. */
    private String provider = "s3";

    /** Custom endpoint URL of a non-AWS S3-compatible store. Leave blank for real AWS S3. */
    private String endpoint;

    /** Signing region (a placeholder like {@code us-east-1} for a store that ignores it). */
    @NotBlank
    private String region = "ap-south-1";

    /** Access key / access key ID. */
    private String accessKey;

    /** Secret key / secret access key. */
    private String secretKey;

    /** Default bucket for user-service assets. */
    @NotBlank
    private String bucket = "user-service-assets";

    /**
     * Single shared bucket for all cached report artifacts across resource types
     * (staff, operators, …). The resource type is encoded as a path
     * segment inside the bucket, not as a separate bucket — so this name should
     * stay generic. Default matches the plan's recommended naming.
     */
    @NotBlank
    private String reportsBucket = "jalsoochak-reports";

    /**
     * Public base URL for presigned GET URLs returned to clients.
     * Use this when the store is behind a reverse proxy or NAT and the internal
     * {@code storage.endpoint} differs from the externally reachable address.
     * When set, the scheme+host+port of the SDK-generated URL is replaced with
     * this value before returning it to callers. Leave blank to use the URL
     * exactly as produced by the SDK (i.e. based on {@code storage.endpoint}).
     */
    private String presignedBaseUrl;

    /** TTL (seconds) for presigned GET URLs returned to clients. */
    @Positive
    private long presignedTtlSeconds = 3600L;
}
