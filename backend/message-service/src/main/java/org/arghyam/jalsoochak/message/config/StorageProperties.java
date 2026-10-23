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

    /** The external store document sends are staged in, bound from {@code storage.delivery.*}. */
    private Delivery delivery = new Delivery();

    /**
     * An optional second S3-compatible store, for a deployment whose store of record Meta cannot
     * download from. Every report is still stored in the store of record under its usual key. A report
     * sent as a WhatsApp document is also copied here, and the copy's URL is the one handed to the
     * provider. Link sends keep the store-of-record URL, which their approved template is frozen to.
     *
     * <p>Every value is required while the store is enabled, or startup fails. The bucket is never
     * created by this service. It must already exist, allow anonymous reads of objects but not
     * listing, and expire objects after a few days. The copies carry officer and Jal Mitra phone
     * numbers, and only the random path segment in each key keeps them from being found.
     */
    @Data
    public static class Delivery {

        /** Set to {@code true} to stage document sends in this store. */
        private boolean enabled = false;

        /** URL of the S3-compatible store, reached with path-style access. */
        private String endpoint;

        /** Signing region. No default: a hosted store signs with its real region. */
        private String region;

        /** Access key / access key ID. */
        private String accessKey;

        /** Secret key / secret access key. */
        private String secretKey;

        /** The one pre-created bucket every staged copy goes to. */
        private String bucket;

        /**
         * Base URL of the anonymously readable address the staged copies are fetched from:
         * {@code <publicBaseUrl>/<bucket>/<objectKey>}. Meta downloads it, so it must be public.
         */
        private String publicBaseUrl;
    }
}
