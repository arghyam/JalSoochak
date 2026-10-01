package org.arghyam.jalsoochak.scheme.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings, bound from {@code storage.*}.
 *
 * <p>Setting {@code storage.endpoint} to a non-blank URL activates path-style
 * access and endpoint override, required by most non-AWS S3-compatible
 * stores. Leave it blank to use real AWS S3.
 */
@ConfigurationProperties(prefix = "storage")
@Data
@Validated
public class StorageProperties {

    /** Set to {@code true} to activate object storage. */
    private boolean enabled = false;

    /** Custom endpoint URL of a non-AWS S3-compatible store. Leave blank for real AWS S3. */
    private String endpoint;

    /** Signing region (a placeholder like {@code us-east-1} for a store that ignores it). */
    @NotBlank
    private String region = "us-east-1";

    /** Access key / access key ID. */
    private String accessKey;

    /** Secret key / secret access key. */
    private String secretKey;

    /** Bucket holding the generated scheme reports. */
    @NotBlank
    private String reportsBucket = "jalsoochak-reports";

    /**
     * Public base URL for presigned GET URLs returned to clients, for a store behind a
     * reverse proxy or NAT whose internal {@code storage.endpoint} differs from its external
     * address. Must be an absolute {@code http(s)} URL with a host and no user-info, query or
     * fragment, or startup fails. Leave blank to return the URL exactly as the SDK signs it.
     */
    private String presignedBaseUrl;

    /** TTL (seconds) for presigned GET URLs returned to clients. */
    @Positive
    private long presignedTtlSeconds = 3600L;
}
