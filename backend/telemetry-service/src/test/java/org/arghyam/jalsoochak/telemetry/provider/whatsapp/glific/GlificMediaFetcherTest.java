package org.arghyam.jalsoochak.telemetry.provider.whatsapp.glific;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Glific side of an inbound media fetch: the media id becomes one path segment under the
 * configured media base URL, and the request carries the API token. Whether the response is usable,
 * and whether a failure is worth another attempt, is the caller's call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMediaFetcher")
class GlificMediaFetcherTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static final String MEDIA_BASE_URL = "https://api.glific.org/v1/media";

    @Mock
    private RestTemplate restTemplate;

    private GlificMediaFetcher fetcher(String token) {
        return new GlificMediaFetcher(restTemplate, MEDIA_BASE_URL, token);
    }

    private void mediaEndpointReturns(ResponseEntity<byte[]> response) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<Void> capturedRequest() {
        ArgumentCaptor<HttpEntity<Void>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET), captor.capture(), eq(byte[].class));
        return captor.getValue();
    }

    @Nested
    @DisplayName("media id validation")
    class Validation {

        @Test
        void rejectsAMediaIdThatWouldWalkOutOfItsPathSegment() {
            // Appended to the media base URL, so "../.." would address a different Glific endpoint.
            assertThatThrownBy(() -> fetcher("token").fetch("../../admin"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid media");

            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(byte[].class));
        }

        @Test
        void acceptsTheOrdinaryMediaIdShapes() {
            mediaEndpointReturns(ResponseEntity.ok(IMAGE));

            assertThat(fetcher("token").fetch("555").getBody()).isEqualTo(IMAGE);
            assertThat(fetcher("token").fetch("9f8e-4c1a-b2d3").getBody()).isEqualTo(IMAGE);
        }
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        void fetchesFromTheConfiguredMediaBaseUrl() {
            when(restTemplate.exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(fetcher("token").fetch("media-1").getBody()).isEqualTo(IMAGE);
        }

        @Test
        void toleratesATrailingSlashOnTheConfiguredBaseUrl() {
            mediaEndpointReturns(ResponseEntity.ok(IMAGE));

            new GlificMediaFetcher(restTemplate, MEDIA_BASE_URL + "/", "token").fetch("media-1");

            verify(restTemplate).exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class));
        }

        @Test
        void sendsTheConfiguredApiTokenAsABearerCredential() {
            mediaEndpointReturns(ResponseEntity.ok(IMAGE));

            fetcher("secret-token").fetch("media-1");

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer secret-token");
        }

        @Test
        void omitsTheAuthorizationHeaderWhenNoTokenIsConfigured() {
            mediaEndpointReturns(ResponseEntity.ok(IMAGE));

            fetcher("  ").fetch("media-1");

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void identifiesItselfWithAUserAgent() {
            mediaEndpointReturns(ResponseEntity.ok(IMAGE));

            fetcher("token").fetch("media-1");

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo("WaterSupplyBot/1.0");
        }
    }

    @Nested
    @DisplayName("response")
    class Response {

        @Test
        void returnsANonOkResponseForTheCallerToJudge() {
            mediaEndpointReturns(new ResponseEntity<>(HttpStatus.NO_CONTENT));

            assertThat(fetcher("token").fetch("media-1").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        void letsATransportErrorPropagateForTheCallerToRetry() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(new ResourceAccessException("connection reset"));

            assertThatThrownBy(() -> fetcher("token").fetch("media-1"))
                    .isInstanceOf(ResourceAccessException.class);
        }
    }
}
