package org.arghyam.jalsoochak.user.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Provider-agnostic abstraction over object storage.
 * The default implementation is S3-compatible (AWS S3, MinIO, Cloudflare R2,
 * DigitalOcean Spaces, GCS interop mode, …).
 *
 * <p>All methods take an explicit {@code bucket} so a single service instance
 * can route objects to different buckets (e.g. assets vs. report cache)
 * without needing one bean per bucket.
 */
public interface ObjectStorageService {

    /**
     * Uploads an object to the given bucket and returns its storage key.
     *
     * @param bucket        target bucket name
     * @param objectKey     storage key (path within the bucket)
     * @param content       object byte stream
     * @param contentLength byte count of the stream
     * @param contentType   MIME type
     * @return the object key that was stored
     */
    String upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType);

    /** Deletes an object by its key. No-op if the object does not exist. */
    void delete(String bucket, String objectKey);

    /**
     * Downloads an object. <strong>Caller must close the returned stream.</strong>
     */
    InputStream download(String bucket, String objectKey);

    /**
     * Generates a short-lived presigned GET URL for the given object.
     *
     * <p>The URL is never persisted by the platform; it is intended for
     * direct return to an authenticated client (e.g. browser download link).
     */
    default URI presignedGetUrl(String bucket, String objectKey, Duration ttl) {
        return presignedGetUrl(bucket, objectKey, ttl, null);
    }

    /**
     * Variant that additionally encodes a download filename via the
     * {@code response-content-disposition} response-override parameter, so
     * the browser saves the object under {@code downloadFilename} regardless
     * of the opaque object key. Pass {@code null} for default browser behavior.
     *
     * @param bucket           bucket the object lives in
     * @param objectKey        object key to sign
     * @param ttl              URL validity duration
     * @param downloadFilename optional user-facing filename for the {@code Content-Disposition}
     *                         header on the GET response; {@code null} omits the override
     */
    URI presignedGetUrl(String bucket, String objectKey, Duration ttl, String downloadFilename);
}
