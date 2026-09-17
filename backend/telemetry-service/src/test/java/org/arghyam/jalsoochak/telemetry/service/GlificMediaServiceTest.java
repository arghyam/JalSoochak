package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMediaServiceTest {

    private static final String IMAGE_URL = "https://example.com/image.jpg";

    @Mock
    private MinioService minioService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestTemplate mediaFetchRestTemplate;

    @Mock
    private MediaUrlValidator mediaUrlValidator;

    private GlificMediaService service;

    @BeforeEach
    void setUp() {
        when(mediaUrlValidator.validate(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        service = new GlificMediaService(
                minioService,
                restTemplate,
                mediaFetchRestTemplate,
                mediaUrlValidator,
                "https://api.glific.org/v1/media",
                3,
                0,
                200,
                400,
                20_971_520L,
                "token"
        );
    }

    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.OngoingStubbing<ResponseEntity<byte[]>> whenMediaFetched() {
        return when((ResponseEntity<byte[]>) mediaFetchRestTemplate.execute(any(URI.class), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)));
    }

    @SuppressWarnings("unchecked")
    private void verifyMediaFetches(int expected) {
        verify(mediaFetchRestTemplate, times(expected)).execute(any(URI.class), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class));
    }

    @Test
    void downloadImageFromUrlDoesNotRetryOnNonRetriable4xx() {
        whenMediaFetched().thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThrows(IOException.class, () -> service.downloadImage(null, IMAGE_URL));

        verifyMediaFetches(1);
    }

    @Test
    void downloadImageFromUrlRetriesTransientFailureAndSucceeds() throws IOException {
        byte[] expected = new byte[]{1, 2, 3};
        whenMediaFetched()
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(ResponseEntity.ok(expected));

        byte[] actual = service.downloadImage(null, IMAGE_URL);

        assertArrayEquals(expected, actual);
        verifyMediaFetches(2);
    }

    @Test
    void downloadImageFromUrlRetriesOnNetworkErrorsUpToMaxAttempts() {
        whenMediaFetched().thenThrow(new ResourceAccessException("timeout"));

        assertThrows(IOException.class, () -> service.downloadImage(null, IMAGE_URL));

        verifyMediaFetches(3);
    }
}
