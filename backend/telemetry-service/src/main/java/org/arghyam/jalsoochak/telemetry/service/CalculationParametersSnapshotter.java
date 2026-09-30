package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.telemetry.repository.ActivePump;
import org.arghyam.jalsoochak.telemetry.repository.SchemeCalculationInputRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Takes the {@link CalculationParameters} snapshot an ELM or PDU reading is published with: the
 * tenant's ELM formula, the scheme's {@code k_factor} and its active pumps.
 *
 * <p>A snapshot is taken every time a reading is published, so a corrected reading is calculated with
 * the pump data current at the time of the correction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalculationParametersSnapshotter {

    /** Written by tenant-service as {@code {"formula": "F1" | "F2" | "F3"}}. */
    static final String ELM_FORMULA_CONFIG_KEY = "ELM_WATER_QUANTITY_FORMULA";
    private static final Set<String> ELM_FORMULAS = Set.of("F1", "F2", "F3");

    private final SchemeCalculationInputRepository schemeCalculationInputRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * @return the snapshot for an ELM or PDU reading; null for every other channel, whose water
     *         quantity isn't calculated from pump data
     */
    public CalculationParameters snapshot(String schemaName, Integer tenantId, Long schemeId, ReadingChannel channel) {
        if (channel != ReadingChannel.ELM && channel != ReadingChannel.PDU) {
            return null;
        }
        List<CalculationParameters.Pump> pumps = schemeCalculationInputRepository
                .findActivePumps(schemaName, schemeId)
                .stream()
                .map(CalculationParametersSnapshotter::toPump)
                .toList();
        return new CalculationParameters(
                CalculationParameters.VERSION,
                channel == ReadingChannel.ELM ? elmFormula(tenantId) : null,
                schemeCalculationInputRepository.findKFactor(schemaName, schemeId).orElse(null),
                pumps);
    }

    /**
     * The tenant's ELM formula code, or null when it hasn't set one. There is no default formula, so an
     * unreadable value is null too, and analytics leaves the tenant's ELM days without a total.
     */
    private String elmFormula(Integer tenantId) {
        String raw = tenantConfigRepository.findConfigValue(tenantId, ELM_FORMULA_CONFIG_KEY).orElse(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode formula = objectMapper.readTree(raw).path("formula");
            if (formula.isTextual()) {
                String code = formula.asText().trim().toUpperCase(Locale.ROOT);
                if (ELM_FORMULAS.contains(code)) {
                    return code;
                }
            }
        } catch (JsonProcessingException e) {
            // Reported below, with every other value that isn't a formula code.
        }
        log.warn("elm_formula_unreadable tenantId={} configKey={}; ELM water quantity is not calculated until it is fixed",
                tenantId, ELM_FORMULA_CONFIG_KEY);
        return null;
    }

    private static CalculationParameters.Pump toPump(ActivePump pump) {
        return new CalculationParameters.Pump(
                pump.id(),
                pump.pumpDischargeCapacity(),
                pump.pumpEfficiency(),
                pump.pumpHead(),
                pump.motorPower(),
                pump.motorPowerUnit(),
                pump.motorEfficiency(),
                pump.unitsConsumedPerHour());
    }
}
