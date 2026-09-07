package org.arghyam.jalsoochak.analytics.helper;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AnalyticsControllerHelper {

    private static final Pattern NON_FILENAME_SAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern MULTIPLE_UNDERSCORES = Pattern.compile("_+");

    private AnalyticsControllerHelper() {
    }

    public static String buildSchemeRegionReportCsv(SchemeRegularityListResponse response) {
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append("scheme_id,scheme_name,state_scheme_id,centre_scheme_id,status_code,status,supply_days,average_regularity,submission_days,submission_rate")
                .append('\n');
        if (response.getSchemes() == null || response.getSchemes().isEmpty()) {
            return csvBuilder.toString();
        }
        for (SchemeRegularityListResponse.SchemeMetrics scheme : response.getSchemes()) {
            csvBuilder.append(toCsvField(scheme.getSchemeId())).append(',')
                    .append(toCsvField(scheme.getSchemeName())).append(',')
                    .append(toCsvField(scheme.getStateSchemeId())).append(',')
                    .append(toCsvField(scheme.getCentreSchemeId())).append(',')
                    .append(toCsvField(scheme.getStatusCode())).append(',')
                    .append(toCsvField(scheme.getStatus())).append(',')
                    .append(toCsvField(scheme.getSupplyDays())).append(',')
                    .append(toCsvField(scheme.getAverageRegularity())).append(',')
                    .append(toCsvField(scheme.getSubmissionDays())).append(',')
                    .append(toCsvField(scheme.getSubmissionRate())).append('\n');
        }
        return csvBuilder.toString();
    }

    public static String buildSchemeRegionReportFilename(
            SchemeRegularityListResponse response, LocalDate startDate, LocalDate endDate) {
        String parentName = response.getParentLgdCName();
        if (parentName == null || parentName.isBlank()) {
            parentName = response.getParentDepartmentCName();
        }
        String safeParentName = sanitizeFilenamePart(parentName);
        return "scheme-region-report_" + safeParentName + "_" + startDate + "_to_" + endDate + ".csv";
    }

    public static String buildSchemeDashboardFilename(
            String scope, Integer scopeId, LocalDate startDate, LocalDate endDate) {
        return "scheme-dashboard_" + sanitizeFilenamePart(scope) + "_" + scopeId + "_" + startDate + "_to_" + endDate + ".csv";
    }

    public static String buildSchemeDashboardCsvHeader() {
        return "scheme_id,scheme_name,status_code,status,submission_days,reporting_rate,total_water_supplied_liters,"
                + "immediate_parent_lgd_id,immediate_parent_lgd_c_name,immediate_parent_lgd_title,immediate_parent_lgd_level,"
                + "immediate_parent_department_id,immediate_parent_department_c_name,immediate_parent_department_title,immediate_parent_department_level,"
                + "level_1_lgd_id,level_2_lgd_id,level_3_lgd_id,level_4_lgd_id,level_5_lgd_id,level_6_lgd_id,"
                + "level_1_dept_id,level_2_dept_id,level_3_dept_id,level_4_dept_id,level_5_dept_id,level_6_dept_id";
    }

    public static String buildSchemeDashboardCsvRow(
            Integer schemeId,
            String schemeName,
            Integer statusCode,
            String status,
            Integer submissionDays,
            BigDecimal reportingRate,
            Long totalWaterSupplied,
            Integer immediateParentLgdId,
            String immediateParentLgdCName,
            String immediateParentLgdTitle,
            Integer immediateParentLgdLevel,
            Integer immediateParentDepartmentId,
            String immediateParentDepartmentCName,
            String immediateParentDepartmentTitle,
            Integer immediateParentDepartmentLevel,
            Integer level1LgdId,
            Integer level2LgdId,
            Integer level3LgdId,
            Integer level4LgdId,
            Integer level5LgdId,
            Integer level6LgdId,
            Integer level1DeptId,
            Integer level2DeptId,
            Integer level3DeptId,
            Integer level4DeptId,
            Integer level5DeptId,
            Integer level6DeptId) {
        return toCsvField(schemeId) + ','
                + toCsvField(schemeName) + ','
                + toCsvField(statusCode) + ','
                + toCsvField(status) + ','
                + toCsvField(submissionDays) + ','
                + toCsvField(reportingRate) + ','
                + toCsvField(totalWaterSupplied) + ','
                + toCsvField(immediateParentLgdId) + ','
                + toCsvField(immediateParentLgdCName) + ','
                + toCsvField(immediateParentLgdTitle) + ','
                + toCsvField(immediateParentLgdLevel) + ','
                + toCsvField(immediateParentDepartmentId) + ','
                + toCsvField(immediateParentDepartmentCName) + ','
                + toCsvField(immediateParentDepartmentTitle) + ','
                + toCsvField(immediateParentDepartmentLevel) + ','
                + toCsvField(level1LgdId) + ','
                + toCsvField(level2LgdId) + ','
                + toCsvField(level3LgdId) + ','
                + toCsvField(level4LgdId) + ','
                + toCsvField(level5LgdId) + ','
                + toCsvField(level6LgdId) + ','
                + toCsvField(level1DeptId) + ','
                + toCsvField(level2DeptId) + ','
                + toCsvField(level3DeptId) + ','
                + toCsvField(level4DeptId) + ','
                + toCsvField(level5DeptId) + ','
                + toCsvField(level6DeptId);
    }

    public static UUID extractAuthenticatedUserUuid(JwtAuthenticationToken authentication) {
        String subject = authentication == null ? null : authentication.getToken().getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authenticated user UUID is required");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Authenticated user UUID is invalid");
        }
    }

    public static AuthenticatedUserRef extractAuthenticatedUserRef(JwtAuthenticationToken authentication) {
        if (authentication == null || authentication.getToken() == null) {
            throw new IllegalArgumentException("Authenticated user details are required");
        }

        Map<String, Object> claims = authentication.getToken().getClaims();
        Integer userIdFromClaim = parsePositiveInteger(claims.get("user_id"));
        if (userIdFromClaim != null) {
            return new AuthenticatedUserRef(userIdFromClaim, null, null);
        }

        String subject = authentication.getToken().getSubject();
        Integer userIdFromSubject = parsePositiveInteger(subject);
        if (userIdFromSubject != null) {
            return new AuthenticatedUserRef(userIdFromSubject, null, null);
        }

        if (subject != null && !subject.isBlank()) {
            try {
                return new AuthenticatedUserRef(null, UUID.fromString(subject), null);
            } catch (IllegalArgumentException ignored) {
                // Try explicit uuid claim before failing.
            }
        }

        Object uuidClaim = claims.get("uuid");
        if (uuidClaim instanceof String uuidText && !uuidText.isBlank()) {
            try {
                return new AuthenticatedUserRef(null, UUID.fromString(uuidText), null);
            } catch (IllegalArgumentException ignored) {
                // fall through to throw below
            }
        }

        throw new IllegalArgumentException("Authenticated user reference is invalid");
    }

    /**
     * Gets the current user's tenant state code from the JWT
     * {@code tenant_state_code} claim. Returns {@code null} if the claim is
     * absent (e.g. for SUPER_USER tokens that carry no tenant). Throws if
     * called outside an authenticated request context.
     */
    /**
     * Gets the current user's tenant state code from the JWT
     * {@code tenant_state_code} claim. Returns {@code null} if the claim is
     * absent (e.g. for SUPER_USER tokens that carry no tenant). Throws if
     * called outside an authenticated request context.
     */
    public static String getCurrentUserTenantStateCode(JwtAuthenticationToken authentication) {
        if (authentication == null || authentication.getToken() == null) {
            throw new IllegalArgumentException("getCurrentUserTenantStateCode() called outside an authenticated request context");
        }
        Map<String, Object> claims = authentication.getToken().getClaims();
        Object tenantStateCode = claims.get("tenant_state_code");
        if (tenantStateCode instanceof String s && !s.isBlank()) {
            return s;
        }
        return null; // explicitly allow null for cases where the claim isn't present
    }

    private static Integer parsePositiveInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                int parsed = Integer.parseInt(trimmed);
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record AuthenticatedUserRef(Integer userId, UUID userUuid, Integer tenantId) {

    }

    private static String sanitizeFilenamePart(String input) {
        if (input == null || input.isBlank()) {
            return "unknown_parent";
        }
        String normalized = NON_FILENAME_SAFE_CHARS.matcher(input.trim().toLowerCase()).replaceAll("_");
        normalized = MULTIPLE_UNDERSCORES.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_|_$", "");
        return normalized.isBlank() ? "unknown_parent" : normalized;
    }

    private static String toCsvField(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
