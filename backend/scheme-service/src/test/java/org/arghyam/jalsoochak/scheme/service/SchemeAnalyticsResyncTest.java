package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.SchemeAnalyticsResyncResponseDTO;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository.MappedLocation;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository.SchemeAnalyticsRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeAnalyticsResyncTest {

    private static final String SCHEMA = "tenant_ka";

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    KafkaProducer kafkaProducer;

    @InjectMocks
    SchemeServiceImpl schemeService;

    @Captor
    ArgumentCaptor<Map<String, Object>> payloadCaptor;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void resync_sendsEverySchemeWithItsVillagesAndSubDivisions_underTheTenantsId() {
        signInAsAdminOf("ka");
        when(schemeDbRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(200);
        when(schemeDbRepository.findAllSchemeIds(SCHEMA)).thenReturn(List.of(1, 2));
        MappedLocation village = new MappedLocation(501, 1, 11, 111, 1111, 11111, 501);
        MappedLocation subDivision = new MappedLocation(1001, 2, 22, 222, 1001, null, null);
        when(schemeDbRepository.findSchemeVillagesBySchemeIds(SCHEMA, List.of(1, 2)))
                .thenReturn(Map.of(1, List.of(village)));
        when(schemeDbRepository.findSchemeSubDivisionsBySchemeIds(SCHEMA, List.of(1, 2)))
                .thenReturn(Map.of(1, List.of(subDivision)));
        when(schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds(SCHEMA, List.of(1, 2)))
                .thenReturn(List.of(scheme(1), scheme(2)));
        when(kafkaProducer.publishJson(anyString(), anyString(), any())).thenReturn(true);

        SchemeAnalyticsResyncResponseDTO response = schemeService.resyncSchemesToAnalytics();

        assertThat(response.getTotalSchemes()).isEqualTo(2);
        assertThat(response.getSentSchemes()).isEqualTo(2);
        verify(kafkaProducer).publishJson(eq("scheme-service-topic"), eq("200:1"), payloadCaptor.capture());
        verify(kafkaProducer).publishJson(eq("scheme-service-topic"), eq("200:2"), payloadCaptor.capture());
        verifyNoMoreInteractions(kafkaProducer);
        assertThat(payloadCaptor.getAllValues().get(0))
                .containsEntry("eventType", "SCHEME_MAPPINGS_REPLACED")
                .containsEntry("schemeId", 1)
                .containsEntry("tenantId", 200)
                .containsEntry("work_status", 2)
                .containsEntry("villages", List.of(village))
                .containsEntry("subDivisions", List.of(subDivision));
        assertThat(payloadCaptor.getAllValues().get(1))
                .containsEntry("eventType", "SCHEME_MAPPINGS_REPLACED")
                .containsEntry("schemeId", 2)
                .containsEntry("villages", List.of())
                .containsEntry("subDivisions", List.of());
    }

    @Test
    void resync_readsTheSchemesInGroupsOfAThousand() {
        signInAsAdminOf("ka");
        List<Integer> schemeIds = IntStream.rangeClosed(1, 1001).boxed().toList();
        when(schemeDbRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(200);
        when(schemeDbRepository.findAllSchemeIds(SCHEMA)).thenReturn(schemeIds);

        SchemeAnalyticsResyncResponseDTO response = schemeService.resyncSchemesToAnalytics();

        assertThat(response.getTotalSchemes()).isEqualTo(1001);
        verify(schemeDbRepository).findSchemeVillagesBySchemeIds(SCHEMA, schemeIds.subList(0, 1000));
        verify(schemeDbRepository).findSchemeVillagesBySchemeIds(SCHEMA, List.of(1001));
        verify(schemeDbRepository).findSchemeSubDivisionsBySchemeIds(SCHEMA, schemeIds.subList(0, 1000));
        verify(schemeDbRepository).findSchemeSubDivisionsBySchemeIds(SCHEMA, List.of(1001));
        verify(schemeDbRepository).findSchemeAnalyticsRowsBySchemeIds(SCHEMA, schemeIds.subList(0, 1000));
        verify(schemeDbRepository).findSchemeAnalyticsRowsBySchemeIds(SCHEMA, List.of(1001));
    }

    @Test
    void resync_countsOnlyTheSchemesKafkaTookIn() {
        signInAsAdminOf("ka");
        when(schemeDbRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(200);
        when(schemeDbRepository.findAllSchemeIds(SCHEMA)).thenReturn(List.of(1, 2));
        when(schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds(SCHEMA, List.of(1, 2)))
                .thenReturn(List.of(scheme(1), scheme(2)));
        when(kafkaProducer.publishJson(anyString(), eq("200:1"), any())).thenReturn(true);
        when(kafkaProducer.publishJson(anyString(), eq("200:2"), any())).thenReturn(false);

        SchemeAnalyticsResyncResponseDTO response = schemeService.resyncSchemesToAnalytics();

        assertThat(response.getTotalSchemes()).isEqualTo(2);
        assertThat(response.getSentSchemes()).isEqualTo(1);
    }

    @Test
    void resync_rejectsATenantMissingFromTheTenantTable() {
        signInAsAdminOf("ka");
        when(schemeDbRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(null);

        assertThatThrownBy(() -> schemeService.resyncSchemesToAnalytics())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(schemeDbRepository, never()).findAllSchemeIds(any());
        verifyNoInteractions(kafkaProducer);
    }

    @Test
    void resync_rejectsAnAdminOfAnotherTenant() {
        TenantContext.setSchema(SCHEMA);
        authenticate("up");

        assertThatThrownBy(() -> schemeService.resyncSchemesToAnalytics())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(schemeDbRepository, kafkaProducer);
    }

    @Test
    void resync_requiresTheTenantHeader() {
        authenticate("ka");

        assertThatThrownBy(() -> schemeService.resyncSchemesToAnalytics())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(schemeDbRepository, kafkaProducer);
    }

    private void signInAsAdminOf(String tenantStateCode) {
        TenantContext.setSchema("tenant_" + tenantStateCode);
        authenticate(tenantStateCode);
        when(schemeDbRepository.findUserIdByEmail("tenant_" + tenantStateCode, "admin@example.com")).thenReturn(10);
    }

    private static void authenticate(String tenantStateCode) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("email", "admin@example.com")
                .claim("tenant_state_code", tenantStateCode)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    private static SchemeAnalyticsRow scheme(int schemeId) {
        return new SchemeAnalyticsRow(schemeId, "10" + schemeId, "20" + schemeId, "Scheme " + schemeId,
                10, 12, 30, 11.11, 22.22, 2, 1);
    }
}
