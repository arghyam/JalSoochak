package org.arghyam.jalsoochak.telemetry.service.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualReadingMaxValuesTest {

    private static final int TENANT_ID = 7;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private ManualReadingMaxValues maxValues;

    @BeforeEach
    void setUp() {
        maxValues = new ManualReadingMaxValues(tenantConfigRepository, new ObjectMapper());
        lenient().when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());
    }

    private void tenantConfig(String json) {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, ManualReadingMaxValues.TENANT_KEY))
                .thenReturn(Optional.of(json));
    }

    private void systemConfig(String json) {
        when(tenantConfigRepository.findConfigValue(0, ManualReadingMaxValues.SYSTEM_KEY))
                .thenReturn(Optional.of(json));
    }

    @Test
    @DisplayName("nothing configured means no limit")
    void nothingConfigured() {
        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.BFM)).isEmpty();
    }

    @Test
    @DisplayName("the tenant's value is used")
    void tenantValue() {
        tenantConfig("{\"maxValues\":{\"BFM\":50000}}");

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.BFM)).contains(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("the system value applies when the tenant has none")
    void systemValue() {
        systemConfig("{\"maxValues\":{\"ELM\":\"9999999\"}}");

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.ELM)).contains(new BigDecimal("9999999"));
    }

    @Test
    @DisplayName("the tenant's value wins over the system's")
    void tenantWinsOverSystem() {
        tenantConfig("{\"maxValues\":{\"BFM\":50000}}");
        lenient().when(tenantConfigRepository.findConfigValue(0, ManualReadingMaxValues.SYSTEM_KEY))
                .thenReturn(Optional.of("{\"maxValues\":{\"BFM\":100000}}"));

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.BFM)).contains(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("levels merge channel by channel: a channel the tenant leaves out takes the system's value")
    void mergesPerChannel() {
        tenantConfig("{\"maxValues\":{\"BFM\":50000}}");
        systemConfig("{\"maxValues\":{\"BFM\":100000,\"PDU\":720}}");

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.PDU)).contains(new BigDecimal("720"));
    }

    @Test
    @DisplayName("a channel set at neither level has no limit")
    void channelSetNowhere() {
        tenantConfig("{\"maxValues\":{\"BFM\":50000}}");
        systemConfig("{\"maxValues\":{\"ELM\":100}}");

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.PDU)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "{\"maxValues\":null}", "{\"maxValues\":{\"BFM\":\"abc\"}}",
            "{\"maxValues\":{\"BFM\":0}}", "{\"maxValues\":{\"BFM\":-5}}", "{\"maxValues\":{\"BFM\":null}}", ""})
    @DisplayName("an unusable tenant value is ignored, and the system value applies")
    void unusableTenantValueFallsBack(String json) {
        tenantConfig(json);
        systemConfig("{\"maxValues\":{\"BFM\":100000}}");

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.BFM)).contains(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("a failed config read means no limit rather than a failed submission")
    void readFailureMeansNoLimit() {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, ManualReadingMaxValues.TENANT_KEY))
                .thenThrow(new DataAccessResourceFailureException("down"));
        when(tenantConfigRepository.findConfigValue(0, ManualReadingMaxValues.SYSTEM_KEY))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(maxValues.maxFor(TENANT_ID, ReadingChannel.BFM)).isEmpty();
    }

    @Test
    @DisplayName("with no tenant only the system value is read")
    void noTenant() {
        systemConfig("{\"maxValues\":{\"BFM\":100000}}");

        assertThat(maxValues.maxFor(null, ReadingChannel.BFM)).contains(new BigDecimal("100000"));
    }
}
