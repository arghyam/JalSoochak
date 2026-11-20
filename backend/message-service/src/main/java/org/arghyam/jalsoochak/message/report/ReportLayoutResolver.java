package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.layout.ReportBlock;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSettings;
import org.arghyam.jalsoochak.message.report.layout.RoleLayout;
import org.arghyam.jalsoochak.message.report.layout.SituationReportLayoutConfig;
import org.arghyam.jalsoochak.message.report.render.ReportFormats;
import org.arghyam.jalsoochak.message.repository.TenantReportLayoutRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses the layout and settings one situation report is drawn with, from the tenant's
 * {@code SITUATION_REPORT_LAYOUT} config.
 *
 * <ul>
 *   <li>No config, or no block for the report: the built-in layout for the role, exactly as before
 *       the config existed. A daily report to a Sub-Divisional Officer has none, so it is not sent.</li>
 *   <li>A block for the report: the roles it lists get their layout, and any other role gets none.</li>
 *   <li>Tenant settings override the environment's dashboard URL and support phone, field by field,
 *       and replace the built-in formats.</li>
 * </ul>
 *
 * <p>The config is read for every report, with no cache, so a change applies from the next run.
 * tenant-service validates it on write. If this service still cannot use it (the two catalogues have
 * drifted, or the row was edited by hand), it is treated as absent: an ERROR, a
 * {@value #FALLBACK_METRIC} count, and the built-in layouts with the environment's settings. The
 * officer still gets a report.</p>
 */
@Component
@Slf4j
public class ReportLayoutResolver {

    /** Counts reports drawn with the built-ins because the tenant's config was unusable. */
    static final String FALLBACK_METRIC = "report.layout.fallback";

    /** The only config version this service understands. */
    static final int SUPPORTED_VERSION = 1;

    private final TenantReportLayoutRepository repository;
    private final DefaultReportLayouts defaults;
    private final ObjectMapper reader;
    private final MeterRegistry meterRegistry;
    private final EnvSettings dailyEnv;
    private final EnvSettings weeklyEnv;

    public ReportLayoutResolver(
            TenantReportLayoutRepository repository,
            DefaultReportLayouts defaults,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${daily-report.dashboard-url:https://jalsoochak.jjmbrain.in/staff/}") String dailyDashboardUrl,
            @Value("${daily-report.support-phone:}") String dailySupportPhone,
            @Value("${weekly-report.dashboard-url:${daily-report.dashboard-url:https://jalsoochak.jjmbrain.in/staff/}}")
            String weeklyDashboardUrl,
            @Value("${weekly-report.support-phone:${daily-report.support-phone:}}") String weeklySupportPhone) {
        this.repository = repository;
        this.defaults = defaults;
        // Lenient whatever the application mapper is: a property added by a newer tenant-service
        // must not send a working tenant back to the built-ins.
        this.reader = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.meterRegistry = meterRegistry;
        this.dailyEnv = new EnvSettings(dailyDashboardUrl, dailySupportPhone);
        this.weeklyEnv = new EnvSettings(weeklyDashboardUrl, weeklySupportPhone);
    }

    /**
     * The layout and settings for a {@code kind} report to an officer of {@code officerUserType}.
     *
     * @param officerUserType matched ignoring case and surrounding blanks
     * @return empty when this officer gets no such report
     * @throws org.springframework.dao.DataAccessException if the config cannot be read
     */
    public Optional<ResolvedReportLayout> resolve(int tenantId, ReportKind kind, String officerUserType) {
        Optional<String> stored = repository.findConfigValue(tenantId);
        if (stored.isEmpty()) {
            return builtIn(kind, officerUserType, null);
        }
        try {
            return fromConfig(parse(stored.get()), kind, officerUserType);
        } catch (UnusableConfigException e) {
            log.error("[ReportLayout] tenant={} report={} role={}: SITUATION_REPORT_LAYOUT is unusable, so the"
                            + " built-in layouts are used: {}",
                    tenantId, kind, roleTag(officerUserType), e.getMessage());
            meterRegistry.counter(FALLBACK_METRIC, "report", kind.name().toLowerCase(Locale.ROOT)).increment();
            return builtIn(kind, officerUserType, null);
        }
    }

    private SituationReportLayoutConfig parse(String json) {
        SituationReportLayoutConfig config;
        try {
            config = reader.readValue(json, SituationReportLayoutConfig.class);
        } catch (JsonProcessingException e) {
            // The original message only: the full one quotes the stored document.
            throw new UnusableConfigException("does not parse: " + e.getOriginalMessage());
        }
        if (config == null) {
            throw new UnusableConfigException("is JSON null");
        }
        if (config.version() == null || config.version() != SUPPORTED_VERSION) {
            throw new UnusableConfigException("version " + config.version() + " is not supported");
        }
        return config;
    }

    private Optional<ResolvedReportLayout> fromConfig(SituationReportLayoutConfig config, ReportKind kind,
                                                      String officerUserType) {
        ReportSettings settings = config.settings();
        ReportBlock block = kind == ReportKind.DAILY ? config.daily() : config.weekly();
        if (block == null) {
            return builtIn(kind, officerUserType, settings);
        }
        String path = kind.name().toLowerCase(Locale.ROOT);
        if (block.roles() == null) {
            throw new UnusableConfigException(path + ".roles is missing");
        }
        Optional<ReportRole> role = ReportRole.fromUserType(officerUserType);
        RoleLayout entry = role.map(block.roles()::get).orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        String entryPath = path + ".roles." + role.get();
        ReportLayout layout = layoutOf(entry, kind, role.get(), entryPath);
        return Optional.of(resolved(layout, kind, settings));
    }

    private ReportLayout layoutOf(RoleLayout entry, ReportKind kind, ReportRole role, String path) {
        if (entry.useDefault() == (entry.layout() != null)) {
            throw new UnusableConfigException(path + ": exactly one of useDefault and layout must be set");
        }
        if (entry.useDefault()) {
            return defaults.builtIn(kind, role.name()).orElseThrow(() ->
                    new UnusableConfigException(path + ".useDefault: there is no built-in layout for this role"));
        }
        List<String> problems = LayoutSanityCheck.problems(kind, role, entry.layout());
        if (!problems.isEmpty()) {
            throw new UnusableConfigException(path + ".layout: " + String.join("; ", problems));
        }
        return entry.layout();
    }

    private Optional<ResolvedReportLayout> builtIn(ReportKind kind, String officerUserType, ReportSettings settings) {
        return defaults.builtIn(kind, officerUserType).map(layout -> resolved(layout, kind, settings));
    }

    /** {@code settings} may be {@code null}: the environment's URL and phone, the built-in formats. */
    private ResolvedReportLayout resolved(ReportLayout layout, ReportKind kind, ReportSettings settings) {
        EnvSettings env = kind == ReportKind.DAILY ? dailyEnv : weeklyEnv;
        if (settings == null) {
            return new ResolvedReportLayout(layout, ReportFormats.defaults(), env.dashboardUrl(), env.supportPhone());
        }
        ReportFormats formats;
        try {
            formats = ReportFormats.of(settings.dateFormats(), settings.numberFormat());
        } catch (IllegalArgumentException e) {
            throw new UnusableConfigException("settings.dateFormats: " + e.getMessage());
        }
        return new ResolvedReportLayout(layout, formats,
                settings.dashboardUrl() != null ? settings.dashboardUrl() : env.dashboardUrl(),
                settings.supportPhone() != null ? settings.supportPhone() : env.supportPhone());
    }

    private static String roleTag(String officerUserType) {
        return officerUserType == null || officerUserType.isBlank() ? "UNKNOWN" : officerUserType.trim();
    }

    /** One report's dashboard URL and support phone from the environment. */
    private record EnvSettings(String dashboardUrl, String supportPhone) {
    }

    /** The stored config cannot be used for this report; the message says why, without quoting it. */
    private static final class UnusableConfigException extends RuntimeException {

        UnusableConfigException(String message) {
            super(message, null, false, false);
        }
    }
}
