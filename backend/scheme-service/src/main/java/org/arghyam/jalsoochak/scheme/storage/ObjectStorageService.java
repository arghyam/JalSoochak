package org.arghyam.jalsoochak.scheme.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Provider-agnostic port over the object store that holds generated reports.
 *
 * <p>Every method takes an explicit {@code bucket}, because the caller records the bucket
 * alongside the object key of each cached report.
 */
public interface ObjectStorageService {

    /**
     * Uploads an object.
     *
     * @param bucket        target bucket
     * @param objectKey     storage key (path within the bucket)
     * @param content       object byte stream
     * @param contentLength byte count of the stream
     * @param contentType   MIME type
     * @throws org.arghyam.jalsoochak.scheme.exception.StorageException when the store rejects the upload
     */
    void upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType);

    /**
     * Generates a short-lived presigned GET URL for an object, for direct return to an
     * authenticated client. The URL is never persisted.
     *
     * @param bucket           bucket the object lives in
     * @param objectKey        object key to sign
     * @param ttl              URL validity duration
     * @param downloadFilename optional user-facing filename, sent as an attachment
     *                         {@code Content-Disposition} on the GET response so the browser saves
     *                         the object under it rather than the opaque key; {@code null} or blank
     *                         omits the override
     * @throws org.arghyam.jalsoochak.scheme.exception.StorageException when signing fails
     */
    URI presignedGetUrl(String bucket, String objectKey, Duration ttl, String downloadFilename);
}
