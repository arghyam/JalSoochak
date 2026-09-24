package org.arghyam.jalsoochak.telemetry.config;

import jakarta.validation.constraints.NotBlank;
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

    /** Bucket holding inbound meter images. */
    @NotBlank
    private String bucket = "jalsoochak";

    /**
     * Base URL of the anonymously readable address that
     * {@code ObjectStorageService.publicUrl} builds on:
     * {@code <publicBaseUrl>/<bucket>/<objectKey>}.
     */
    private String publicBaseUrl;
}
