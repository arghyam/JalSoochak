package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the pluggable per-tenant behaviour of the built-in FlowVision extractor: the resolved
 * {@link OcrProviderSettings} decide the endpoint hit and the auth header sent.
 */
class FlowVisionServiceProviderSettingsTest {

    private static final String DEFAULT_URL = "https://default/extract";

    @Test
    void usesPerTenantEndpointAndAuthHeaderFromSettings() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(successResponse());

        FlowVisionService service = new FlowVisionService(restTemplate, DEFAULT_URL);
        OcrProviderSettings settings =
                new OcrProviderSettings("vision-x", "https://vision-x/extract", "secret-token", "X-Api-Key");

        FlowVisionResult result = service.extractReading("https://img", settings);

        assertNotNull(result);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));

        assertEquals("https://vision-x/extract", urlCaptor.getValue());
        assertEquals("secret-token", entityCaptor.getValue().getHeaders().getFirst("X-Api-Key"));
    }

    @Test
    void defaultPathUsesGlobalEndpointAndSendsNoAuthHeader() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(successResponse());

        FlowVisionService service = new FlowVisionService(restTemplate, DEFAULT_URL);

        service.extractReading("https://img");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));

        assertEquals(DEFAULT_URL, urlCaptor.getValue());
        assertNull(entityCaptor.getValue().getHeaders().getFirst("Authorization"));
    }

    private static ResponseEntity<Map> successResponse() {
        Map<String, Object> data = new HashMap<>();
        data.put("meterReading", "123.4");
        data.put("lastDigitColor", "black");
        data.put("qualityStatus", "good");
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "SUCCESS");
        resultMap.put("data", data);
        resultMap.put("correlationId", "corr-1");
        Map<String, Object> body = new HashMap<>();
        body.put("result", resultMap);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }
}
