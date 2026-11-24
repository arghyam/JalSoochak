package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The built-in situation report layouts: the reports every tenant gets unless its
 * {@code SITUATION_REPORT_LAYOUT} config says otherwise, drawn by the same renderer as a tenant's own.
 *
 * <p>Loaded once at startup, and a broken file fails startup. The files ship in the jar, so a broken
 * one is a build defect, and a service that started without it would have no report to fall back
 * on. The parse is strict, so a misspelt property fails here rather than silently dropping a
 * column.</p>
 */
@Component
public class DefaultReportLayouts {

    static final String DAILY_SECTION_OFFICER = "report-layouts/daily-section-officer.json";
    static final String WEEKLY_SECTION_OFFICER = "report-layouts/weekly-section-officer.json";
    static final String WEEKLY_SUB_DIVISIONAL_OFFICER = "report-layouts/weekly-sub-divisional-officer.json";

    private final ReportLayout dailySectionOfficer;
    private final ReportLayout weeklySectionOfficer;
    private final ReportLayout weeklySubDivisionalOfficer;

    public DefaultReportLayouts(ObjectMapper objectMapper) {
        ObjectMapper strict = strict(objectMapper);
        this.dailySectionOfficer = load(strict, new ClassPathResource(DAILY_SECTION_OFFICER),
                ReportKind.DAILY, ReportRole.SECTION_OFFICER);
        this.weeklySectionOfficer = load(strict, new ClassPathResource(WEEKLY_SECTION_OFFICER),
                ReportKind.WEEKLY, ReportRole.SECTION_OFFICER);
        this.weeklySubDivisionalOfficer = load(strict, new ClassPathResource(WEEKLY_SUB_DIVISIONAL_OFFICER),
                ReportKind.WEEKLY, ReportRole.SUB_DIVISIONAL_OFFICER);
    }

    /**
     * The built-in layout for a report sent to an officer of {@code officerUserType}: the SDO layout
     * for a Sub-Divisional Officer, the Section Officer one for any other role.
     *
     * @return empty for a daily report to a Sub-Divisional Officer, which has no built-in layout
     */
    public Optional<ReportLayout> builtIn(ReportKind kind, String officerUserType) {
        boolean sdo = ReportRole.fromUserType(officerUserType)
                .filter(role -> role == ReportRole.SUB_DIVISIONAL_OFFICER)
                .isPresent();
        return switch (kind) {
            case DAILY -> sdo ? Optional.empty() : Optional.of(dailySectionOfficer);
            case WEEKLY -> Optional.of(sdo ? weeklySubDivisionalOfficer : weeklySectionOfficer);
        };
    }

    /** A copy of the application's mapper that rejects unknown properties and trailing content. */
    static ObjectMapper strict(ObjectMapper objectMapper) {
        return objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws IllegalStateException if the file is missing, does not parse, or fails the sanity check */
    static ReportLayout load(ObjectMapper strict, Resource resource, ReportKind kind, ReportRole role) {
        ReportLayout layout;
        try (InputStream in = resource.getInputStream()) {
            layout = strict.readValue(in, ReportLayout.class);
        } catch (IOException e) {
            throw new IllegalStateException("Built-in report layout " + resource.getDescription()
                    + " cannot be read: " + e.getMessage(), e);
        }
        List<String> problems = LayoutSanityCheck.problems(kind, role, layout);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Built-in report layout " + resource.getDescription()
                    + " is invalid for " + kind + " " + role + ": " + String.join("; ", problems));
        }
        return layout;
    }
}
