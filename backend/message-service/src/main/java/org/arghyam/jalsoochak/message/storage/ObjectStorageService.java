package org.arghyam.jalsoochak.message.storage;

import java.io.InputStream;
import java.net.URI;

/**
 * Provider-agnostic port over the object store that holds report PDFs.
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
     * @throws org.arghyam.jalsoochak.message.exception.StorageException when the store rejects the upload
     */
    void upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType);

    /**
     * Returns the permanent, unsigned URL of an object under {@code storage.public-base-url}, for
     * consumers that fetch the object themselves and cannot be handed an expiring link — so the
     * bucket must allow anonymous reads. Each key segment is percent-encoded and the {@code /}
     * separators are kept. No network call is made.
     *
     * @throws org.arghyam.jalsoochak.message.exception.StorageException when {@code storage.public-base-url} is blank or the resulting
     *         URL is malformed
     */
    URI publicUrl(String bucket, String objectKey);
}
