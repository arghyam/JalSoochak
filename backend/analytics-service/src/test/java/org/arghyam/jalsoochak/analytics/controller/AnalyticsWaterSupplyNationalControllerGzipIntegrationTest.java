package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2BoundaryResponse;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = AnalyticsWaterSupplyNationalControllerGzipIntegrationTest.MinimalTestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "analytics.single-tenant-mode=false",
        "server.compression.enabled=true",
        "server.compression.min-response-size=1"
})
class AnalyticsWaterSupplyNationalControllerGzipIntegrationTest {

    @LocalServerPort
    private int port;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @MockBean
    private DateDimensionService dateDimensionService;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void nationalDashboardBoundaryLevel2_whenClientAcceptsGzip_returnsGzippedResponse() throws Exception {
        // Make payload large enough to reasonably compress.
        String bigTitle = "x".repeat(5000);
        when(schemeRegularityService.getNationalDashboardLevel2BoundariesForApi())
                .thenReturn(NationalDashboardLevel2BoundaryResponse.builder()
                        .nationalBoundary(OBJECT_MAPPER.readTree("""
                                {"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}
                                """))
                        .lgdLevel2Boundaries(List.of(
                                NationalDashboardLevel2BoundaryResponse.LgdLevel2Boundary.builder()
                                        .tenantId(1)
                                        .lgdId(101)
                                        .tenantStatus(1)
                                        .stateCode("mp")
                                        .stateTitle("Madhya Pradesh")
                                        .title(bigTitle)
                                        .boundary(OBJECT_MAPPER.readTree("""
                                                {"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}
                                                """))
                                        .build()
                        ))
                        .build());

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/analytics/national/dashboard/boundary/district"))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");

        byte[] unzipped = gunzip(response.body());
        String json = new String(unzipped, StandardCharsets.UTF_8);
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"lgdLevel2Boundaries\"");
    }

    private static byte[] gunzip(byte[] gzipped) throws Exception {
        assertThat(gzipped).isNotNull();
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzipped));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            KafkaAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    })
    @Import({AnalyticsWaterSupplyNationalController.class, GlobalExceptionHandler.class})
    static class MinimalTestApplication {
    }
}

