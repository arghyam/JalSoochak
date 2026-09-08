package org.arghyam.jalsoochak.telemetry.service.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSupplyCounts;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * SUPPLY-PLAUSIBILITY: gathers a scheme's inputs, runs {@link ImplausibleSupplyPolicy}, and owns the
 * observability for the result.
 *
 * <p>It sits between the reading services and the policy so that the numbers behind a rejection —
 * ceiling and population — <strong>never leave this class</strong>. They go into the log line here;
 * the caller receives only which arm of {@link Verdict} came back, so there is no route by which a
 * threshold could reach an API response. That is a structural guarantee rather than a convention,
 * and it is the reason the caller is not simply handed the {@code Quarantined} numbers: an API-key
 * holder who learns the ceiling can solve for the scheme's connection count and the per-person limit
 * in two submissions (THRESHOLD-DISCLOSURE, as documented at {@code GlificMeterWorkflowService}).
 *
 * <p>The mode lives here too, so {@code AUDIT} and {@code ENFORCE} run identical evaluations and
 * differ only in what the caller does afterwards. Under {@code AUDIT} a would-be rejection is logged
 * and counted and nothing else happens: no anomaly row, no quarantine, no rejection. That is
 * deliberate — a week of unvalidated would-be rejections landing on staff dashboards would be
 * triage noise, and the point of the audit period is to size the problem, not to act on it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplyPlausibilityGuard {

    private static final String METRIC_SKIPPED = "implausible_supply.skipped";
    private static final String METRIC_ACCEPTED = "implausible_supply.accepted";
    private static final String METRIC_WOULD_QUARANTINE = "implausible_supply.would_quarantine";
    private static final String METRIC_QUARANTINED = "implausible_supply.quarantined";

    /** Tenant config holding persons per household, e.g. {@code {"value":"4.5"}}. */
    private static final String AVERAGE_MEMBERS_PER_HOUSEHOLD = "AVERAGE_MEMBERS_PER_HOUSEHOLD";

    /** Rejects anything a {@code BigDecimal} would accept but a household size should not. */
    private static final String POSITIVE_DECIMAL = "^\\d+(\\.\\d+)?$";

    /** Which endpoint asked. A metric tag, so the values are fixed and few. */
    public enum Path {
        /** {@code POST /readings} — a new submission. */
        SUBMISSION("submission"),
        /** {@code PUT /readings} — a correction to a stored reading. */
        CORRECTION("correction");

        private final String tag;

        Path(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;
    private final SupplyPlausibilityProperties properties;
    private final MeterRegistry meterRegistry;

    /** @return true when the check must not run at all, so callers can skip gathering inputs. */
    public boolean isDisabled() {
        return properties.isDisabled();
    }

    /** @return true when a {@link Verdict.Quarantined} should actually be acted on. */
    public boolean isEnforcing() {
        return properties.isEnforcing();
    }

    /**
     * Evaluates one reading and records the outcome.
     *
     * <p>The caller supplies the baseline because it needs the same value for the anomaly record —
     * {@code previous_reading} must be the baseline the litres were measured against, not the
     * standing stored value, so that {@code (overridden_reading - previous_reading) * 1000}
     * reproduces the decision from the persisted row alone. Fetching it here as well would risk the
     * two disagreeing.
     *
     * <p>Structural preconditions — the check being enabled, the tenant schema being migrated, the
     * channel being BFM, the meter not having been replaced — are the caller's to apply before
     * calling. Only conditions derivable from the scheme's own data are resolved here.
     *
     * @param baselineReading the latest non-quarantined confirmed reading strictly before this one,
     *                        or {@code null} when the scheme has none
     * @return the verdict; its numbers have already been logged and counted
     */
    public Verdict assess(String schemaName,
                          Integer tenantId,
                          Long operatorId,
                          Long schemeId,
                          LocalDate readingDate,
                          BigDecimal confirmedReading,
                          BigDecimal baselineReading,
                          Path path) {
        TelemetrySchemeSupplyCounts counts = telemetryTenantRepository
                .findSchemeSupplyCounts(schemaName, schemeId)
                .orElse(new TelemetrySchemeSupplyCounts(0, 0, 0));

        SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                confirmedReading,
                baselineReading,
                counts.fhtcCount(),
                counts.plannedFhtc(),
                counts.houseHoldCount(),
                resolveMembersPerHousehold(tenantId).orElse(null),
                properties.getResolvedLimitPerPersonLitres());

        Verdict verdict = ImplausibleSupplyPolicy.evaluate(inputs);
        report(verdict, tenantId, operatorId, schemeId, readingDate, confirmedReading,
                baselineReading, path);
        return verdict;
    }

    /**
     * Persons per household for a tenant: its {@code AVERAGE_MEMBERS_PER_HOUSEHOLD} config, else the
     * configured default, else empty.
     *
     * <p>Parsed with the same defensive shape as {@code BfmReadingService.loadWaterNorm} — a
     * malformed config falls back rather than throwing, because a bad value in one tenant's config
     * must not fail readings. Falling back is safe in the direction that matters: the default is a
     * plausible household size, and an absent default skips the check rather than inventing a
     * ceiling.
     */
    Optional<BigDecimal> resolveMembersPerHousehold(Integer tenantId) {
        return readPositiveDecimalConfig(tenantId, AVERAGE_MEMBERS_PER_HOUSEHOLD)
                .or(() -> Optional.ofNullable(properties.getResolvedDefaultMembersPerHousehold()));
    }

    private Optional<BigDecimal> readPositiveDecimalConfig(Integer tenantId, String key) {
        if (tenantId == null) {
            return Optional.empty();
        }
        Optional<String> raw = tenantConfigRepository.findConfigValue(tenantId, key);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(raw.get());
            String value = root != null ? root.path("value").asText(null) : null;
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String normalized = value.trim().replace(",", "");
            if (!normalized.matches(POSITIVE_DECIMAL)) {
                log.warn("Invalid {} config for tenantId {}; falling back", key, tenantId);
                return Optional.empty();
            }
            BigDecimal parsed = new BigDecimal(normalized);
            return parsed.signum() > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (Exception e) {
            log.warn("Invalid {} config for tenantId {}: {}", key, tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The one place a verdict's numbers are written down. Ceiling and population appear here and
     * nowhere else — not in the response, and not in the anomaly row, which has no numeric field for
     * them. The consequence, stated so it is not discovered later: the ceiling <em>as it stood on
     * the day</em> is not persisted, so a retrospective audit either reads this line or
     * reconstructs an approximate ceiling from current master data.
     */
    private void report(Verdict verdict,
                        Integer tenantId,
                        Long operatorId,
                        Long schemeId,
                        LocalDate readingDate,
                        BigDecimal confirmedReading,
                        BigDecimal baselineReading,
                        Path path) {
        switch (verdict) {
            case Verdict.Skipped skipped -> {
                count(METRIC_SKIPPED, path, "reason", skipped.reason().metricTag());
                if (skipped.reason() == Verdict.SkipReason.NO_POPULATION) {
                    // The only skip that is a fixable data gap rather than a property of the reading.
                    // Loud on purpose: while this fires, enforcing would be a no-op for the scheme.
                    log.warn("implausible_supply_skipped reason={} tenantId={} schemeId={} readingDate={} "
                                    + "path={} — scheme records no FHTC, planned FHTC or household count",
                            skipped.reason().metricTag(), tenantId, schemeId, readingDate, path.tag());
                } else {
                    log.debug("implausible_supply_skipped reason={} tenantId={} schemeId={} readingDate={} path={}",
                            skipped.reason().metricTag(), tenantId, schemeId, readingDate, path.tag());
                }
            }
            case Verdict.Accepted accepted -> {
                count(METRIC_ACCEPTED, path);
                log.debug("implausible_supply_accepted tenantId={} schemeId={} readingDate={} path={} "
                                + "litres={} ceiling={} population={}",
                        tenantId, schemeId, readingDate, path.tag(),
                        accepted.litres(), accepted.ceiling(), accepted.population());
            }
            case Verdict.Quarantined quarantined -> {
                boolean enforcing = isEnforcing();
                count(enforcing ? METRIC_QUARANTINED : METRIC_WOULD_QUARANTINE, path);
                log.warn("{} mode={} path={} tenantId={} schemeId={} operatorId={} readingDate={} "
                                + "submitted={} baseline={} litres={} ceiling={} population={}",
                        enforcing ? "implausible_supply_rejected" : "implausible_supply_would_reject",
                        properties.getResolvedMode(), path.tag(), tenantId, schemeId, operatorId,
                        readingDate, confirmedReading, baselineReading,
                        quarantined.litres(), quarantined.ceiling(), quarantined.population());
            }
        }
    }

    private void count(String metric, Path path, String... extraTags) {
        if (meterRegistry == null) {
            return;
        }
        Counter.Builder builder = Counter.builder(metric)
                .tag("path", path.tag())
                .tag("mode", properties.getResolvedMode().name());
        for (int i = 0; i + 1 < extraTags.length; i += 2) {
            builder = builder.tag(extraTags[i], extraTags[i + 1]);
        }
        builder.register(meterRegistry).increment();
    }
}
