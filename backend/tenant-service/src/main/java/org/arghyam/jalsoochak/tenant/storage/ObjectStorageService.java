package org.arghyam.jalsoochak.tenant.storage;

import java.io.InputStream;

/**
 * Provider-agnostic abstraction over object storage.
 * The default implementation works with AWS S3 and any other store that
 * speaks the S3 API.
 * Additional providers (e.g. Azure Blob) can be added by implementing this
 * interface and activating via the {@code storage.provider} property.
 */
public interface ObjectStorageService {

    /**
     * Uploads an object and returns its storage key.
     *
     * @param objectKey     storage key (path within the bucket), e.g. {@code logos/1/uuid.png}
     * @param content       object byte stream
     * @param contentLength byte count of the stream
     * @param contentType   MIME type, e.g. {@code image/png}
     * @return the object key that was stored
     */
    String upload(String objectKey, InputStream content, long contentLength, String contentType);

    /**
     * Deletes an object by its key. No-op if the object does not exist.
     *
     * @param objectKey storage key of the object to delete
     */
    void delete(String objectKey);

    /**
     * Downloads an object and returns its byte stream.
     * The caller is responsible for closing the stream.
     *
     * @param objectKey storage key of the object to download
     * @return byte stream of the object content
     */
    InputStream download(String objectKey);
}
