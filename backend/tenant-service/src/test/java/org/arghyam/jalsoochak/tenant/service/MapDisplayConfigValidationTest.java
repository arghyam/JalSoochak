package org.arghyam.jalsoochak.tenant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.config.properties.TenantDefaultsProperties;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.RegionTypeEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantSchemaRepository;
import org.arghyam.jalsoochak.tenant.service.serviceImpl.TenantManagementServiceImpl;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for map display config validation in {@link TenantManagementServiceImpl}:
 * - LGD level cascade: level N FALSE forces levels N+1..max FALSE
 * - Dept level cascade: same rule
 * - Hierarchy depth: validation only applies to levels that exist in the tenant's hierarchy
 */
@ExtendWith(MockitoExtension.class)
class MapDisplayConfigValidationTest {

    @Mock private TenantCommonRepository tenantCommonRepository;
    @Mock private TenantSchemaRepository tenantSchemaRepository;
    @Mock private AppProperties appProperties;
    @Mock private TenantDefaultsProperties tenantDefaults;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TenantSchedulerManager schedulerManager;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private SystemManagementService systemManagementService;
    @Mock private ApiKeyService apiKeyService;

    private TenantManagementServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int TENANT_ID = 1;
    private static final String STATE_CODE = "TS";
    private static final String SCHEMA = "tenant_ts";

    @BeforeEach
    void setUp() {
        service = new TenantManagementServiceImpl(
                tenantCommonRepository, tenantSchemaRepository, objectMapper,
                appProperties, tenantDefaults, eventPublisher, schedulerManager,
                objectStorageService, systemManagementService, apiKeyService);

        // Provide a valid authenticated context so resolveCurrentUserId() doesn't throw
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "test-uuid")
                .build();
        var auth = new UsernamePasswordAuthenticationToken(jwt, null, Collections.emptyList());
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        // Common stubs for setTenantConfigs preamble
        TenantResponseDTO tenant = TenantResponseDTO.builder()
                .id(TENANT_ID).stateCode(STATE_CODE).status("CONFIGURED").build();
        when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(tenantCommonRepository.findUserIdByUuid("test-uuid")).thenReturn(Optional.of(99));
        // Default: no persisted map-display configs
        lenient().when(tenantCommonRepository.findConfigByTenantAndKey(eq(TENANT_ID), any()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private LocationConfigDTO lgdHierarchy(int levels) {
        List<LocationLevelConfigDTO> list = new java.util.ArrayList<>();
        for (int i = 1; i <= levels; i++) list.add(LocationLevelConfigDTO.builder().level(i).build());
        return LocationConfigDTO.builder().locationHierarchy(list).build();
    }

    private ConfigDTO persistedConfig(TenantConfigKeyEnum key, String value) throws Exception {
        return ConfigDTO.builder()
                .configKey(key.name())
                .configValue(objectMapper.writeValueAsString(Map.of("value", value)))
                .build();
    }

    private SetTenantConfigRequestDTO requestWith(Map<TenantConfigKeyEnum, String> kvs) throws Exception {
        Map<TenantConfigKeyEnum, JsonNode> nodes = new HashMap<>();
        for (var e : kvs.entrySet()) {
            nodes.put(e.getKey(), objectMapper.readTree("{\"value\":\"" + e.getValue() + "\"}"));
        }
        SetTenantConfigRequestDTO req = new SetTenantConfigRequestDTO();
        req.setConfigs(nodes);
        return req;
    }

    private void stubLgdHierarchy(int levels) {
        when(tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD))
                .thenReturn(lgdHierarchy(levels));
    }

    private void stubDeptHierarchy(int levels) {
        when(tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.DEPARTMENT))
                .thenReturn(lgdHierarchy(levels));
    }

    private void stubUpsert(TenantConfigKeyEnum key, String value) throws Exception {
        when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq(key.name()), any(), eq(99)))
                .thenReturn(Optional.of(persistedConfig(key, value)));
    }

    // ── LGD cascade validation ─────────────────────────────────────────────────

    @Nested
    class LgdCascadeValidation {

        @Test
        void valid_settingLevel1FalseAndLevel2False_passes() throws Exception {
            stubLgdHierarchy(4);
            stubDeptHierarchy(0);
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_4, "FALSE");

            assertThatNoException().isThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_4, "FALSE"))));
        }

        @Test
        void invalid_settingLevel1FalseButLevel2True_throws() throws Exception {
            stubLgdHierarchy(4);

            assertThatThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "TRUE"))))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("DISPLAY_MAP_LGD_LEVEL_2");
        }

        @Test
        void invalid_persistedLevel2FalseAndRequestSetsLevel3True_throws() throws Exception {
            stubLgdHierarchy(4);
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "DISPLAY_MAP_LGD_LEVEL_2"))
                    .thenReturn(Optional.of(persistedConfig(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "FALSE")));

            assertThatThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3, "TRUE"))))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("DISPLAY_MAP_LGD_LEVEL_3");
        }

        @Test
        void levelsAboveHierarchyDepth_areIgnored() throws Exception {
            // Tenant has only 3 LGD levels — levels 4-6 are outside the active window and not validated
            stubLgdHierarchy(3);
            stubDeptHierarchy(0);
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3, "FALSE");
            // Level 4 is above the hierarchy depth so it bypasses cascade validation and is upserted as-is
            stubUpsert(TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_4, "TRUE");

            // LEVEL_4=TRUE with LEVEL_3=FALSE would fail cascade validation if level 4 were active —
            // but since the hierarchy only has 3 levels, LEVEL_4 is ignored in validation and this passes.
            assertThatNoException().isThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_4, "TRUE"))));
        }
    }

    // ── Dept cascade validation ────────────────────────────────────────────────

    @Nested
    class DeptCascadeValidation {

        @Test
        void valid_settingLevel1FalseAndLevel2False_passes() throws Exception {
            stubLgdHierarchy(0);
            stubDeptHierarchy(4);
            stubUpsert(TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_1, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_2, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_3, "FALSE");
            stubUpsert(TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_4, "FALSE");

            assertThatNoException().isThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_1, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_2, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_3, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_4, "FALSE"))));
        }

        @Test
        void invalid_settingLevel2FalseButLevel3True_throws() throws Exception {
            stubLgdHierarchy(0);
            stubDeptHierarchy(4);

            assertThatThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_2, "FALSE",
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_3, "TRUE"))))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("DISPLAY_DEPARTMENT_MAP_LEVEL_3");
        }

        @Test
        void invalid_persistedLevel3FalseAndRequestSetsLevel4True_throws() throws Exception {
            stubLgdHierarchy(0);
            stubDeptHierarchy(4);
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "DISPLAY_DEPARTMENT_MAP_LEVEL_3"))
                    .thenReturn(Optional.of(persistedConfig(TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_3, "FALSE")));

            assertThatThrownBy(() ->
                    service.setTenantConfigs(TENANT_ID, requestWith(Map.of(
                            TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_4, "TRUE"))))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("DISPLAY_DEPARTMENT_MAP_LEVEL_4");
        }

    }
}
