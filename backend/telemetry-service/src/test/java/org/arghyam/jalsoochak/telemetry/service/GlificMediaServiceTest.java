package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMediaServiceTest {

    @Mock
    private MinioService minioService;

    @Mock
    private RestTemplate restTemplate;

    @Test
    void downloadImageFromUrlDoesNotRetryOnNonRetriable4xx() {
        GlificMediaService service = new GlificMediaService(
                minioService,
                restTemplate,
                "https://api.glific.org/v1/media",
                3,
                0,
                200,
                400,
                "token"
        );

        when(restTemplate.exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThrows(IOException.class, () -> service.downloadImage(null, "https://example.com/image.jpg"));

        verify(restTemplate, times(1)).exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        );
    }

    @Test
    void downloadImageFromUrlRetriesTransientFailureAndSucceeds() throws IOException {
        GlificMediaService service = new GlificMediaService(
                minioService,
                restTemplate,
                "https://api.glific.org/v1/media",
                3,
                0,
                200,
                400,
                "token"
        );
        byte[] expected = new byte[]{1, 2, 3};

        when(restTemplate.exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(ResponseEntity.ok(expected));

        byte[] actual = service.downloadImage(null, "https://example.com/image.jpg");

        assertArrayEquals(expected, actual);
        verify(restTemplate, times(2)).exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        );
    }

    @Test
    void downloadImageFromUrlRetriesOnNetworkErrorsUpToMaxAttempts() {
        GlificMediaService service = new GlificMediaService(
                minioService,
                restTemplate,
                "https://api.glific.org/v1/media",
                3,
                0,
                200,
                400,
                "token"
        );

        when(restTemplate.exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenThrow(new ResourceAccessException("timeout"));

        assertThrows(IOException.class, () -> service.downloadImage(null, "https://example.com/image.jpg"));

        verify(restTemplate, times(3)).exchange(
                eq("https://example.com/image.jpg"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        );
    }
}
