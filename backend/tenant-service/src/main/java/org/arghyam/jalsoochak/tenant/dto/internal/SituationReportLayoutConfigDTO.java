package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportBlockDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportSettingsDTO;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for the {@code SITUATION_REPORT_LAYOUT} tenant config key: which officers get the daily and
 * weekly Water Service Situation Reports, and the layout each one gets. message-service reads it for
 * every report event and draws the PDF from it.
 *
 * <p>An absent {@code daily} or {@code weekly} block leaves that report as it is without the config:
 * recipient roles from env, built-in layouts. {@code {"version": 1}} therefore resets both.</p>
 *
 * <p>The config write path binds with {@code ObjectMapper.treeToValue}, which runs no bean validation
 * and, under Spring Boot's mapper, drops unknown properties. The enforced checks are in
 * {@code SituationReportLayoutValidator}.</p>
 *
 * @param version  must be 1
 * @param settings tenant-wide URL, phone and formats; absent means env values and built-in formats
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SituationReportLayoutConfigDTO(Integer version, ReportSettingsDTO settings, ReportBlockDTO daily,
                                             ReportBlockDTO weekly) implements ConfigValueDTO {
}
