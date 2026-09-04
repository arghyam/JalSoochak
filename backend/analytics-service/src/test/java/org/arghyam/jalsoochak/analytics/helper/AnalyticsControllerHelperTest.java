package org.arghyam.jalsoochak.analytics.helper;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusDTO;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsControllerHelperTest {

    @Test
    void buildSchemeRegionReportCsv_returnsHeaderAndEscapedValues() {
        SchemeRegularityListResponse response = SchemeRegularityListResponse.builder()
                .schemes(List.of(
                        SchemeRegularityListResponse.SchemeMetrics.builder()
                                .schemeId(1)
                                .schemeName("Scheme, \"A\"")
                                .stateSchemeId(10001)
                                .centreSchemeId(20001)
                                .workStatus(SchemeStatusDTO.builder().code(1).label("Ongoing").build())
                                .operatingStatus(SchemeStatusDTO.builder().code(2).label("Partially Operative").build())
                                .supplyDays(2)
                                .averageRegularity(BigDecimal.valueOf(0.6667))
                                .submissionDays(3)
                                .submissionRate(BigDecimal.valueOf(1.0000))
                                .build(),
                        SchemeRegularityListResponse.SchemeMetrics.builder()
                                .schemeId(2)
                                .schemeName("Scheme B\nNorth")
                                .stateSchemeId(null)
                                .centreSchemeId(null)
                                .workStatus(null)
                                .operatingStatus(SchemeStatusDTO.builder().code(0).label("Non-Operative").build())
                                .supplyDays(null)
                                .averageRegularity(BigDecimal.ZERO)
                                .submissionDays(1)
                                .submissionRate(BigDecimal.valueOf(0.3333))
                                .build()))
                .build();

        String csv = AnalyticsControllerHelper.buildSchemeRegionReportCsv(response);

        assertEquals(
                "scheme_id,scheme_name,state_scheme_id,centre_scheme_id,"
                        + "work_status_code,work_status,operating_status_code,operating_status,"
                        + "supply_days,average_regularity,submission_days,submission_rate\n"
                        + "1,\"Scheme, \"\"A\"\"\",10001,20001,1,Ongoing,2,Partially Operative,2,0.6667,3,1.0\n"
                        + "2,\"Scheme B\nNorth\",,,,,0,Non-Operative,,0,1,0.3333\n",
                csv);
    }

    @Test
    void buildSchemeRegionReportFilename_prefersParentLgdNameAndSanitizes() {
        SchemeRegularityListResponse response = SchemeRegularityListResponse.builder()
                .parentLgdCName(" Parent LGD (Zone 1) ")
                .parentDepartmentCName("Department HQ")
                .build();

        String filename = AnalyticsControllerHelper.buildSchemeRegionReportFilename(
                response, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals("scheme-region-report_parent_lgd_zone_1_2026-01-01_to_2026-01-31.csv", filename);
    }

    @Test
    void buildSchemeRegionReportFilename_fallsBackToUnknownParent() {
        SchemeRegularityListResponse response = SchemeRegularityListResponse.builder()
                .parentDepartmentCName("   ")
                .build();

        String filename = AnalyticsControllerHelper.buildSchemeRegionReportFilename(
                response, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals("scheme-region-report_unknown_parent_2026-01-01_to_2026-01-31.csv", filename);
    }

    @Test
    void extractAuthenticatedUserUuid_returnsUuidFromJwtSubject() {
        UUID userUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

        UUID extractedUuid = AnalyticsControllerHelper.extractAuthenticatedUserUuid(buildAuthentication(userUuid.toString()));

        assertEquals(userUuid, extractedUuid);
    }

    @Test
    void extractAuthenticatedUserUuid_rejectsMissingOrInvalidSubjects() {
        IllegalArgumentException missingSubject = assertThrows(
                IllegalArgumentException.class,
                () -> AnalyticsControllerHelper.extractAuthenticatedUserUuid(buildAuthentication(" ")));
        IllegalArgumentException invalidSubject = assertThrows(
                IllegalArgumentException.class,
                () -> AnalyticsControllerHelper.extractAuthenticatedUserUuid(buildAuthentication("not-a-uuid")));

        assertEquals("Authenticated user UUID is required", missingSubject.getMessage());
        assertEquals("Authenticated user UUID is invalid", invalidSubject.getMessage());
    }

    @Test
    void extractAuthenticatedUserRef_prefersNumericUserIdClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("not-a-uuid")
                .claim("user_id", 77)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        AnalyticsControllerHelper.AuthenticatedUserRef ref =
                AnalyticsControllerHelper.extractAuthenticatedUserRef(new JwtAuthenticationToken(jwt));

        assertEquals(77, ref.userId());
        assertNull(ref.userUuid());
        assertNull(ref.tenantId());
    }

    @Test
    void extractAuthenticatedUserRef_acceptsNumericSubject() {
        AnalyticsControllerHelper.AuthenticatedUserRef ref =
                AnalyticsControllerHelper.extractAuthenticatedUserRef(buildAuthentication("42"));

        assertEquals(42, ref.userId());
        assertNull(ref.userUuid());
        assertNull(ref.tenantId());
    }

    @Test
    void extractAuthenticatedUserRef_fallsBackToUuidClaim() {
        String uuid = "22222222-2222-2222-2222-222222222222";
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("not-a-uuid")
                .claims(claims -> claims.putAll(Map.of("uuid", uuid)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        AnalyticsControllerHelper.AuthenticatedUserRef ref =
                AnalyticsControllerHelper.extractAuthenticatedUserRef(new JwtAuthenticationToken(jwt));

        assertNull(ref.userId());
        assertEquals(UUID.fromString(uuid), ref.userUuid());
        assertNull(ref.tenantId());
    }

    @Test
    void buildSchemeDashboardCsv_carriesBothStatusDimensions() {
        assertEquals(
                "scheme_id,scheme_name,work_status_code,work_status,operating_status_code,operating_status,"
                        + "submission_days,reporting_rate,total_water_supplied_liters,"
                        + "immediate_parent_lgd_id,immediate_parent_lgd_c_name,immediate_parent_lgd_title,immediate_parent_lgd_level,"
                        + "immediate_parent_department_id,immediate_parent_department_c_name,immediate_parent_department_title,immediate_parent_department_level,"
                        + "level_1_lgd_id,level_2_lgd_id,level_3_lgd_id,level_4_lgd_id,level_5_lgd_id,level_6_lgd_id,"
                        + "level_1_dept_id,level_2_dept_id,level_3_dept_id,level_4_dept_id,level_5_dept_id,level_6_dept_id",
                AnalyticsControllerHelper.buildSchemeDashboardCsvHeader());

        String row = AnalyticsControllerHelper.buildSchemeDashboardCsvRow(
                schemeSubmissionMetrics(2, 3), BigDecimal.valueOf(0.5));

        // Partially Operative survives the export; under the old binary it was flattened to "active".
        assertTrue(row.startsWith("1,Scheme A,3,Not Started,2,Partially Operative,10,0.5,150,"), row);
    }

    @Test
    void buildSchemeDashboardCsvRow_labelsUnrecordedStatusesUnknown() {
        String row = AnalyticsControllerHelper.buildSchemeDashboardCsvRow(
                schemeSubmissionMetrics(null, null), BigDecimal.valueOf(0.5));

        assertTrue(row.startsWith("1,Scheme A,,Unknown,,Unknown,10,0.5,150,"), row);
    }

    private static SchemeRegularityRepository.SchemeSubmissionMetrics schemeSubmissionMetrics(
            Integer operatingStatus, Integer workStatus) {
        return new SchemeRegularityRepository.SchemeSubmissionMetrics(
                1, "Scheme A", operatingStatus, workStatus, 10, 150L,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    private static JwtAuthenticationToken buildAuthentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
