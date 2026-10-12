package org.arghyam.jalsoochak.tenant.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.validation.ChannelValidator;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * DTO for the {@code TENANT_MANUAL_READING_MAX_VALUE} (tenant) and {@code MANUAL_READING_MAX_VALUE}
 * (system) config keys: the largest reading value that may be typed in or asserted, per reading channel.
 * Shape: {@code {"maxValues":{"BFM":"99999999","ELM":"9999999","PDU":"720"}}}.
 *
 * <p>Each value is in the channel's standard unit (BFM m3, ELM kWh, PDU minutes). A channel left out
 * falls back to the system value, and then to no limit. telemetry-service enforces it.</p>
 *
 * <p>The enforced path is {@link #validatedMaxValues()}: the config write path binds with
 * {@code ObjectMapper.treeToValue}, which does not trigger bean validation. Mirrors
 * {@code RegularityThresholdConfigDTO.validatedThresholdPercent()}.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class ManualReadingMaxValueConfigDTO implements ConfigValueDTO {

    /** A pump can't run for more than a day in one run; telemetry refuses that regardless of this config. */
    static final BigDecimal PDU_MAX_MINUTES = new BigDecimal("1440");

    /** Channel code (BFM, ELM, PDU, IOT, MAN) to the largest value accepted on that channel. */
    private Map<String, BigDecimal> maxValues;

    /**
     * @return the limits keyed by upper-case channel code; empty means no limit on any channel
     * @throws InvalidConfigValueException if the map is missing, names an unknown channel or the same
     *                                     channel twice, or holds a value that is not positive, or a PDU
     *                                     value above 1440 minutes
     */
    public Map<String, BigDecimal> validatedMaxValues() {
        if (maxValues == null) {
            throw new InvalidConfigValueException(
                    "MANUAL_READING_MAX_VALUE must set maxValues, e.g. {\"maxValues\":{\"BFM\":\"99999999\"}}");
        }
        Map<String, BigDecimal> normalized = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : maxValues.entrySet()) {
            String channel = entry.getKey() == null ? null : entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (channel == null || !ChannelValidator.VALID_CHANNELS.contains(channel)) {
                throw new InvalidConfigValueException("Unknown channel '" + entry.getKey()
                        + "' in MANUAL_READING_MAX_VALUE. Allowed channels are: BFM, ELM, PDU, IOT, MAN");
            }
            BigDecimal max = entry.getValue();
            if (max == null || max.signum() <= 0) {
                throw new InvalidConfigValueException("Maximum for " + channel
                        + " in MANUAL_READING_MAX_VALUE must be a number greater than 0");
            }
            if ("PDU".equals(channel) && max.compareTo(PDU_MAX_MINUTES) > 0) {
                throw new InvalidConfigValueException(
                        "Maximum for PDU in MANUAL_READING_MAX_VALUE can't be more than 1440 minutes");
            }
            if (normalized.put(channel, max) != null) {
                throw new InvalidConfigValueException(
                        "Channel " + channel + " is given more than once in MANUAL_READING_MAX_VALUE");
            }
        }
        return normalized;
    }
}
