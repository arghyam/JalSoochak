package org.arghyam.jalsoochak.user.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.exceptions.StorageException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * S3-compatible implementation of {@link ObjectStorageService}.
 * Works with any provider exposing the AWS S3 API (AWS S3, MinIO,
 * Cloudflare R2, DigitalOcean Spaces, Wasabi, …).
 */
@Slf4j
@RequiredArgsConstructor
public class S3CompatibleStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    /**
     * Optional public-facing base URL (e.g. {@code https://jalsoochak.in/minio}) used to
     * rewrite the SDK-generated presigned URL after signing. The signature itself is computed
     * against the internal endpoint so the reverse proxy can validate it correctly by forwarding
     * {@code Host: <internal-host>} to MinIO. Only the origin and path prefix are swapped;
     * the query string (including X-Amz-Signature and X-Amz-Expires) is preserved verbatim.
     */
    private final String presignedBaseUrl;

    @Override
    public String upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType) {
        try {
            log.debug("[Storage] Uploading [bucket={}, key={}, contentType={}, size={}]",
                    bucket, objectKey, contentType, contentLength);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
            return objectKey;
        } catch (SdkException e) {
            throw new StorageException("Upload failed for key: " + objectKey, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build());
        } catch (NoSuchKeyException e) {
            log.debug("[Storage] Object already absent [bucket={}, key={}]", bucket, objectKey);
        } catch (SdkException e) {
            throw new StorageException("Delete failed for key: " + objectKey, e);
        }
    }

    @Override
    public InputStream download(String bucket, String objectKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new StorageException("Object not found: " + objectKey, e);
        } catch (SdkException e) {
            throw new StorageException("Download failed for key: " + objectKey, e);
        }
    }

    @Override
    public URI presignedGetUrl(String bucket, String objectKey, Duration ttl, String downloadFilename) {
        try {
            GetObjectRequest.Builder getBuilder = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey);
            if (downloadFilename != null && !downloadFilename.isBlank()) {
                getBuilder.responseContentDisposition(contentDisposition(downloadFilename));
            }
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getBuilder.build())
                    .build();
            URI sdkUri = s3Presigner.presignGetObject(presignRequest).url().toURI();
            return rewritePublicUrl(sdkUri);
        } catch (Exception e) {
            throw new StorageException("Failed to presign URL for key: " + objectKey, e);
        }
    }

    /**
     * Rewrites the SDK-generated presigned URL to use the public-facing base URL.
     * Only the origin (scheme + host + port) and any path prefix declared in
     * {@code presignedBaseUrl} are replaced; the object path and the entire query
     * string (X-Amz-Signature, X-Amz-Expires, …) are preserved verbatim so the
     * signature and TTL remain valid.
     *
     * <p>Requires the reverse proxy to forward {@code Host: <internal-host>} to
     * MinIO (e.g. nginx {@code proxy_set_header Host $proxy_host;}) so that MinIO
     * reconstructs the canonical request with the same host that was signed.
     */
    URI rewritePublicUrl(URI sdkUri) {
        if (presignedBaseUrl == null || presignedBaseUrl.isBlank()) {
            return sdkUri;
        }
        try {
            URI base = URI.create(presignedBaseUrl.strip());
            String rawBasePath = base.getPath() != null ? base.getPath() : "";
            if (rawBasePath.endsWith("/")) {
                rawBasePath = rawBasePath.substring(0, rawBasePath.length() - 1);
            }
            // Build via string concatenation to preserve the raw (percent-encoded) query string.
            // The multi-arg URI constructor decodes then re-encodes the query, which corrupts
            // presigned parameters such as X-Amz-Credential that contain encoded slashes (%2F).
            StringBuilder sb = new StringBuilder();
            sb.append(base.getScheme()).append("://").append(base.getHost());
            if (base.getPort() != -1) {
                sb.append(":").append(base.getPort());
            }
            sb.append(rawBasePath).append(sdkUri.getRawPath());
            if (sdkUri.getRawQuery() != null) {
                sb.append("?").append(sdkUri.getRawQuery());
            }
            return URI.create(sb.toString());
        } catch (Exception e) {
            log.warn("[Storage] Failed to rewrite presigned URL — returning SDK URL. Cause: {}", e.getMessage());
            return sdkUri;
        }
    }

    /**
     * RFC 6266 {@code Content-Disposition} value. Sanitizes characters that
     * would break the header (quote, CR, LF) and falls back to the RFC 5987
     * {@code filename*} parameter for non-ASCII filenames so unicode names
     * survive the round trip without being mangled.
     */
    private static String contentDisposition(String filename) {
        String safeAscii = filename.replaceAll("[\"\\r\\n]", "_");
        String encoded = java.net.URLEncoder.encode(safeAscii, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + safeAscii + "\"; filename*=UTF-8''" + encoded;
    }
}
