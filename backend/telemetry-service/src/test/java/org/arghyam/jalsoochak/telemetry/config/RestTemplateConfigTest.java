package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestTemplateConfigTest {

    @Test
    void restTemplateUsesHttpClientPoolingRequestFactory() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();

        assertNotNull(restTemplate);
        assertInstanceOf(HttpComponentsClientHttpRequestFactory.class, restTemplate.getRequestFactory());
    }
}
