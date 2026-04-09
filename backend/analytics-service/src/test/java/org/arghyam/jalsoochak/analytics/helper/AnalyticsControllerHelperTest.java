package org.arghyam.jalsoochak.analytics.helper;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
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

class AnalyticsControllerHelperTest {

    @Test
    void buildSchemeRegionReportCsv_returnsHeaderAndEscapedValues() {
        SchemeRegularityListResponse response = SchemeRegularityListResponse.builder()
                .schemes(List.of(
                        SchemeRegularityListResponse.SchemeMetrics.builder()
                                .schemeId(1)
                                .schemeName("Scheme, \"A\"")
                                .statusCode(1)
                                .status("active")
                                .supplyDays(2)
                                .averageRegularity(BigDecimal.valueOf(0.6667))
                                .submissionDays(3)
                                .submissionRate(BigDecimal.valueOf(1.0000))
                                .build(),
                        SchemeRegularityListResponse.SchemeMetrics.builder()
                                .schemeId(2)
                                .schemeName("Scheme B\nNorth")
                                .statusCode(0)
                                .status(null)
                                .supplyDays(null)
                                .averageRegularity(BigDecimal.ZERO)
                                .submissionDays(1)
                                .submissionRate(BigDecimal.valueOf(0.3333))
                                .build()))
                .build();

        String csv = AnalyticsControllerHelper.buildSchemeRegionReportCsv(response);

        assertEquals(
                "scheme_id,scheme_name,status_code,status,supply_days,average_regularity,submission_days,submission_rate\n"
                        + "1,\"Scheme, \"\"A\"\"\",1,active,2,0.6667,3,1.0\n"
                        + "2,\"Scheme B\nNorth\",0,,,0,1,0.3333\n",
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
