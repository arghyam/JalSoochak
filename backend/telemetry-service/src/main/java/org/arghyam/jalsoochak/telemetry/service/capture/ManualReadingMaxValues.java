package org.arghyam.jalsoochak.telemetry.service.capture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The largest value that may be typed in or asserted on a reading channel, in the channel's standard
 * unit. Configured per channel as {@code {"maxValues":{"BFM":"99999999"}}}: the tenant's
 * {@code TENANT_MANUAL_READING_MAX_VALUE} first, then the system's {@code MANUAL_READING_MAX_VALUE}
 * (tenant 0), channel by channel. tenant-service validates both on write.
 *
 * <p>A channel set at neither level has no limit. So does an unreadable or malformed value, which is
 * logged and skipped: a broken config must not stop every operator on the tenant from submitting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualReadingMaxValues {

    static final String TENANT_KEY = "TENANT_MANUAL_READING_MAX_VALUE";
    static final String SYSTEM_KEY = "MANUAL_READING_MAX_VALUE";
    private static final int SYSTEM_TENANT_ID = 0;

    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;

    /** @param tenantId null reads the system value only */
    public Optional<BigDecimal> maxFor(Integer tenantId, ReadingChannel channel) {
        if (channel == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> tenantMax = tenantId == null
                ? Optional.empty()
                : read(tenantId, TENANT_KEY, channel);
        return tenantMax.or(() -> read(SYSTEM_TENANT_ID, SYSTEM_KEY, channel));
    }

    private Optional<BigDecimal> read(int tenantId, String key, ReadingChannel channel) {
        String raw;
        try {
            Optional<String> value = tenantConfigRepository.findConfigValue(tenantId, key);
            raw = value == null ? null : value.orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read {} [tenantId={}], applying no limit from it: {}", key, tenantId, e.getMessage());
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode value = objectMapper.readTree(raw).path("maxValues").path(channel.name());
            if (value.isMissingNode() || value.isNull()) {
                return Optional.empty();
            }
            BigDecimal max = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
            if (max.signum() > 0) {
                return Optional.of(max);
            }
        } catch (JsonProcessingException | NumberFormatException e) {
            // Falls through to the warning below.
        }
        log.warn("Ignoring invalid {} for channel {} [tenantId={}]", key, channel, tenantId);
        return Optional.empty();
    }
}
