package org.arghyam.jalsoochak.user.service.report;

import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeSummaryDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.TenantStaffRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single resource-specific file for staff reports: identifier, column
 * schema, filter normalization, and row fetching. Formats are handled by
 * the generic {@code ReportWriter} implementations and are not duplicated
 * here.
 */
@Component
public class StaffReportDefinition implements ReportDefinition<TenantStaffResponseDTO, StaffReportRequestDTO> {

    public static final String TYPE = "TENANT_STAFF";

    private final TenantStaffRepository tenantStaffRepository;
    private final ReportSchema<TenantStaffResponseDTO> schema;

    public StaffReportDefinition(TenantStaffRepository tenantStaffRepository) {
        this.tenantStaffRepository = tenantStaffRepository;
        this.schema = buildSchema();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.STAFF_USERS;
    }

    @Override
    public ReportSchema<TenantStaffResponseDTO> schema() {
        return schema;
    }

    @Override
    public StaffReportRequestDTO normalize(StaffReportRequestDTO in) {
        List<String> normalizedRoles = normalizeRoles(in == null ? null : in.roles());
        String status = in == null || in.status() == null || in.status().isBlank()
                ? null
                : in.status().trim().toUpperCase(Locale.ROOT);
        String name = in == null || in.name() == null || in.name().isBlank()
                ? null
                : in.name().trim();
        return new StaffReportRequestDTO(normalizedRoles, status, name);
    }

    @Override
    public List<TenantStaffResponseDTO> fetch(String tenantSchema, StaffReportRequestDTO filters) {
        return tenantStaffRepository.listAllStaffForExport(
                tenantSchema, filters.roles(), parseStatus(filters.status()), filters.name());
    }

    @Override
    public String downloadFilenamePrefix() {
        return "staff_report";
    }

    private static ReportSchema<TenantStaffResponseDTO> buildSchema() {
        return new ReportSchema<>(List.of(
                new ReportColumn<>("ID", r -> r.id() == null ? "" : r.id().toString()),
                new ReportColumn<>("UUID", TenantStaffResponseDTO::uuid),
                new ReportColumn<>("Name", TenantStaffResponseDTO::title),
                new ReportColumn<>("Email", TenantStaffResponseDTO::email),
                new ReportColumn<>("Phone Number", TenantStaffResponseDTO::phoneNumber),
                new ReportColumn<>("Status", r -> r.status() == null ? "" : r.status().name()),
                new ReportColumn<>("Role", TenantStaffResponseDTO::role),
                new ReportColumn<>("Schemes", r -> formatSchemes(r.schemes()))
        ));
    }

    private static String formatSchemes(List<SchemeSummaryDTO> schemes) {
        if (schemes == null || schemes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SchemeSummaryDTO s : schemes) {
            String name = s.schemeName();
            if (name == null || name.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(name);
        }
        return sb.toString();
    }

    private static List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : roles) {
            if (entry == null || entry.isBlank()) continue;
            for (String part : entry.split(",")) {
                String value = part == null ? "" : part.trim();
                if (!value.isEmpty()) {
                    normalized.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        return normalized.stream().sorted().toList();
    }

    private static Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim();
        if (normalized.matches("^[0-9]+$")) {
            return Integer.parseInt(normalized);
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> TenantUserStatus.ACTIVE.code;
            case "INACTIVE" -> TenantUserStatus.INACTIVE.code;
            default -> throw new BadRequestException("Unknown status: " + status);
        };
    }
}
