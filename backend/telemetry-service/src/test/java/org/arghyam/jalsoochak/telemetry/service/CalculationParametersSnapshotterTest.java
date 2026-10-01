package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.telemetry.repository.ActivePump;
import org.arghyam.jalsoochak.telemetry.repository.SchemeCalculationInputRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculationParametersSnapshotterTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 22;
    private static final long SCHEME_ID = 10L;
    private static final String FORMULA_KEY = "ELM_WATER_QUANTITY_FORMULA";

    private static final ActivePump PUMP = new ActivePump(12L, new BigDecimal("500"), new BigDecimal("0.7"),
            new BigDecimal("40"), new BigDecimal("7.5"), "HP", new BigDecimal("0.85"), new BigDecimal("5"));

    @Mock
    private SchemeCalculationInputRepository schemeCalculationInputRepository;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private CalculationParametersSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        snapshotter = new CalculationParametersSnapshotter(
                schemeCalculationInputRepository, tenantConfigRepository, new ObjectMapper());
    }

    private void schemeHas(BigDecimal kFactor, List<ActivePump> pumps) {
        when(schemeCalculationInputRepository.findKFactor(SCHEMA, SCHEME_ID)).thenReturn(Optional.ofNullable(kFactor));
        when(schemeCalculationInputRepository.findActivePumps(SCHEMA, SCHEME_ID)).thenReturn(pumps);
    }

    private void tenantFormulaIs(String storedValue) {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, FORMULA_KEY)).thenReturn(Optional.ofNullable(storedValue));
    }

    private CalculationParameters snapshot(ReadingChannel channel) {
        return snapshotter.snapshot(SCHEMA, TENANT_ID, SCHEME_ID, channel);
    }

    @ParameterizedTest
    @EnumSource(value = ReadingChannel.class, names = {"BFM", "IOT", "MAN"})
    void aChannelNotCalculatedFromPumpDataHasNoSnapshot(ReadingChannel channel) {
        assertThat(snapshot(channel)).isNull();

        verifyNoInteractions(schemeCalculationInputRepository, tenantConfigRepository);
    }

    /** The values are passed on exactly as stored: analytics does every conversion and check. */
    @Test
    void anElmSnapshotCarriesTheTenantsFormulaTheKFactorAndTheActivePumps() {
        schemeHas(new BigDecimal("0.95"), List.of(PUMP));
        tenantFormulaIs("{\"formula\":\"F2\"}");

        assertThat(snapshot(ReadingChannel.ELM)).isEqualTo(new CalculationParameters(1, "F2", new BigDecimal("0.95"),
                List.of(new CalculationParameters.Pump(12L, new BigDecimal("500"), new BigDecimal("0.7"),
                        new BigDecimal("40"), new BigDecimal("7.5"), "HP", new BigDecimal("0.85"),
                        new BigDecimal("5")))));
    }

    /**
     * A PDU run's litres are its minutes times the discharge rate, so the tenant's ELM formula and the
     * k_factor are neither read nor sent.
     */
    @Test
    void aPduSnapshotCarriesOnlyTheActivePumps() {
        when(schemeCalculationInputRepository.findActivePumps(SCHEMA, SCHEME_ID)).thenReturn(List.of(PUMP));

        CalculationParameters snapshot = snapshot(ReadingChannel.PDU);

        assertThat(snapshot.elmFormula()).isNull();
        assertThat(snapshot.kFactor()).isNull();
        assertThat(snapshot.pumps()).hasSize(1);
        verify(tenantConfigRepository, never()).findConfigValue(any(), anyString());
        verify(schemeCalculationInputRepository, never()).findKFactor(any(), any());
    }

    @Test
    void aNullKFactorAndNoActivePumpAreSentAsTheyAre() {
        schemeHas(null, List.of());
        tenantFormulaIs("{\"formula\":\"F1\"}");

        CalculationParameters snapshot = snapshot(ReadingChannel.ELM);

        assertThat(snapshot.kFactor()).isNull();
        assertThat(snapshot.pumps()).isEmpty();
    }

    @Test
    void aTenantWithoutAFormulaHasNone() {
        schemeHas(null, List.of());
        tenantFormulaIs(null);

        assertThat(snapshot(ReadingChannel.ELM).elmFormula()).isNull();
    }

    @Test
    void theFormulaCodeIsReadWhateverItsCaseAndSpacing() {
        schemeHas(null, List.of());
        tenantFormulaIs("{\"formula\":\" f3 \"}");

        assertThat(snapshot(ReadingChannel.ELM).elmFormula()).isEqualTo("F3");
    }

    /** There is no default formula, so a value that isn't one of the codes is no formula at all. */
    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"formula\":null}", "{\"formula\":\"F4\"}", "{\"formula\":2}", "F1",
            "\"F1\"", "{\"value\":\"F1\"}", "not json"})
    void anUnreadableFormulaIsNone(String storedValue) {
        schemeHas(null, List.of());
        tenantFormulaIs(storedValue);

        assertThat(snapshot(ReadingChannel.ELM).elmFormula()).isNull();
    }
}
