package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserSchemeMappingsReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.LgdLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemePerformanceEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import org.arghyam.jalsoochak.analytics.service.FactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;

@ExtendWith(MockitoExtension.class)
class AnalyticsKafkaConsumerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DimensionService dimensionService;

    @Mock
    private FactService factService;

    @InjectMocks
    private AnalyticsKafkaConsumer consumer;

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void consumeTenantEvents_waterNormUpdated_routesToUpdateWaterNorm() throws Exception {
        String message = """
                {"eventType":"WATER_NORM_UPDATED","tenantId":1,"stateCode":"MP","waterNorm":70}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<WaterNormUpdatedEvent> captor = ArgumentCaptor.forClass(WaterNormUpdatedEvent.class);
        verify(dimensionService).updateWaterNorm(captor.capture());
        WaterNormUpdatedEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(1);
        assertThat(readField(event, "waterNorm")).isEqualTo(70);
        assertThat(readField(event, "stateCode")).isEqualTo("MP");
    }

    @Test
    void consumeTenantEvents_includedWorkStatusesUpdated_routesToUpdateIncludedWorkStatuses() {
        String message = """
                {"eventType":"INCLUDED_WORK_STATUSES_UPDATED","tenantId":1,"stateCode":"MP","workStatuses":[1,4]}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<IncludedWorkStatusesUpdatedEvent> captor =
                ArgumentCaptor.forClass(IncludedWorkStatusesUpdatedEvent.class);
        verify(dimensionService).updateIncludedWorkStatuses(captor.capture());
        IncludedWorkStatusesUpdatedEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(1);
        assertThat(event.getWorkStatuses()).containsExactly(1, 4);
    }

    @Test
    void consumeTenantEvents_includedWorkStatusesUpdated_nationalTenantZero_routesWithNationalSentinel() {
        String message = """
                {"eventType":"INCLUDED_WORK_STATUSES_UPDATED","tenantId":0,"stateCode":"NATIONAL","workStatuses":[1]}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<IncludedWorkStatusesUpdatedEvent> captor =
                ArgumentCaptor.forClass(IncludedWorkStatusesUpdatedEvent.class);
        verify(dimensionService).updateIncludedWorkStatuses(captor.capture());
        assertThat(captor.getValue().getTenantId()).isZero();
        assertThat(captor.getValue().getWorkStatuses()).containsExactly(1);
    }

    @Test
    void consumeSchemeEvents_schemeUpdated_deserializesSnakeCaseWorkStatus() {
        // Producer sends snake_case; @JsonProperty("work_status") must bind it onto SchemeEvent.
        String message = """
                {"eventType":"SCHEME_UPDATED","schemeId":1001,"tenantId":1,"work_status":4}
                """;

        consumer.consumeSchemeEvents(message);

        ArgumentCaptor<SchemeEvent> captor = ArgumentCaptor.forClass(SchemeEvent.class);
        verify(dimensionService).upsertScheme(captor.capture());
        assertThat(captor.getValue().getWorkStatus()).isEqualTo(4);
    }

    @Test
    void consumeUserEvents_userCreated_routesToUpsertUser() throws Exception {
        String message = """
                {"eventType":"USER_CREATED","userId":42,"tenantId":3,"email":"admin@state.gov","userType":2,"uuid":"11111111-1111-1111-1111-111111111111","status":1,"title":"First Last"}
                """;

        consumer.consumeUserEvents(message);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(dimensionService).upsertUser(captor.capture());
        UserEvent event = captor.getValue();
        assertThat(readField(event, "eventType")).isEqualTo("USER_CREATED");
        assertThat(readField(event, "userId")).isEqualTo(42);
        assertThat(readField(event, "tenantId")).isEqualTo(3);
        assertThat(readField(event, "status")).isEqualTo(1);
        assertThat(readField(event, "title")).isEqualTo("First Last");
    }

    @Test
    void consumeUserEvents_userUpdated_routesToUpsertUser() throws Exception {
        String message = """
                {"eventType":"USER_UPDATED","userId":42,"tenantId":3,"email":"admin@state.gov","userType":2,"uuid":"11111111-1111-1111-1111-111111111111","status":0}
                """;

        consumer.consumeUserEvents(message);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(dimensionService).upsertUser(captor.capture());
        UserEvent event = captor.getValue();
        assertThat(readField(event, "eventType")).isEqualTo("USER_UPDATED");
        assertThat(readField(event, "status")).isEqualTo(0);
        assertThat(readField(event, "title")).isNull();
    }

    @Test
    void consumeUserEvents_userSchemeMappingsReplaced_routesToReplaceUserSchemeMappings() {
        String message = """
                {"eventType":"USER_SCHEME_MAPPINGS_REPLACED","userId":42,"tenantId":3,"userUuid":"11111111-1111-1111-1111-111111111111","schemeIds":[1001,1002],"status":1}
                """;

        consumer.consumeUserEvents(message);

        ArgumentCaptor<UserSchemeMappingsReplacedEvent> captor =
                ArgumentCaptor.forClass(UserSchemeMappingsReplacedEvent.class);
        verify(dimensionService).replaceUserSchemeMappings(captor.capture());
        UserSchemeMappingsReplacedEvent event = captor.getValue();
        assertThat(readField(event, "eventType")).isEqualTo("USER_SCHEME_MAPPINGS_REPLACED");
        assertThat(readField(event, "userId")).isEqualTo(42);
        assertThat(readField(event, "tenantId")).isEqualTo(3);
        assertThat(readField(event, "status")).isEqualTo(1);
    }

    @Test
    void consumeUserEvents_unknownEventType_isIgnored() throws Exception {
        String message = """
                {"eventType":"USER_DELETED","userId":42}
                """;

        consumer.consumeUserEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_tenantUpdated_routesToUpsertTenant() throws Exception {
        String message = """
                {"eventType":"TENANT_UPDATED","tenantId":5,"stateCode":"RJ","title":"Rajasthan","status":1}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<TenantEvent> captor = ArgumentCaptor.forClass(TenantEvent.class);
        verify(dimensionService).upsertTenant(captor.capture());
        TenantEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(5);
        assertThat(readField(event, "stateCode")).isEqualTo("RJ");
        assertThat(readField(event, "title")).isEqualTo("Rajasthan");
    }

    @Test
    void consumeTenantEvents_tenantCreated_routesToUpsertTenant() throws Exception {
        String message = """
                {"eventType":"TENANT_CREATED","tenantId":5,"stateCode":"RJ","title":"Rajasthan","status":1}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<TenantEvent> captor = ArgumentCaptor.forClass(TenantEvent.class);
        verify(dimensionService).upsertTenant(captor.capture());
        TenantEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(5);
        assertThat(readField(event, "stateCode")).isEqualTo("RJ");
        assertThat(readField(event, "title")).isEqualTo("Rajasthan");
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_waterSupplyThresholdUpdated_routesToUpdateWaterSupplyThreshold() throws Exception {
        String message = """
                {"eventType":"WATER_SUPPLY_THRESHOLD_UPDATED","tenantId":3,"stateCode":"MH","underSupplyThresholdPercent":20,"overSupplyThresholdPercent":30}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<WaterSupplyThresholdUpdatedEvent> captor =
                ArgumentCaptor.forClass(WaterSupplyThresholdUpdatedEvent.class);
        verify(dimensionService).updateWaterSupplyThreshold(captor.capture());
        WaterSupplyThresholdUpdatedEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(3);
        assertThat(readField(event, "stateCode")).isEqualTo("MH");
        assertThat(readField(event, "underSupplyThresholdPercent")).isEqualTo(20);
        assertThat(readField(event, "overSupplyThresholdPercent")).isEqualTo(30);
    }

    @Test
    void consumeTenantEvents_tenantLocationHierarchyUpdated_routesToUpdateLocationHierarchyNames() throws Exception {
        String message = """
                {
                  "eventType":"TENANT_LOCATION_HIERARCHY_UPDATED",
                  "tenantId":2,"stateCode":"GJ","hierarchyType":"LGD",
                  "levels":[{"level":1,"name":"State"},{"level":2,"name":"District"}]
                }
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<TenantLocationHierarchyUpdatedEvent> captor =
                ArgumentCaptor.forClass(TenantLocationHierarchyUpdatedEvent.class);
        verify(dimensionService).updateLocationHierarchyNames(captor.capture());
        TenantLocationHierarchyUpdatedEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(2);
        assertThat(readField(event, "hierarchyType")).isEqualTo("LGD");
        @SuppressWarnings("unchecked")
        List<Object> levels = (List<Object>) readField(event, "levels");
        assertThat(levels).hasSize(2);
        assertThat(readField(levels.get(0), "name")).isEqualTo("State");
    }

    @Test
    void consumeTenantEvents_missingEventType_isIgnored() {
        String message = """
                {"tenantId":1,"stateCode":"MP"}
                """;

        consumer.consumeTenantEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_unknownEventType_isIgnored() {
        String message = """
                {"eventType":"TENANT_DELETED","tenantId":1}
                """;

        consumer.consumeTenantEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeTenantEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_knownEventType_butInvalidPayload_throwsRuntimeException() {
        String message = """
                {"eventType":"TENANT_UPDATED","tenantId":"not-an-int","stateCode":"MP"}
                """;

        assertThatThrownBy(() -> consumer.consumeTenantEvents(message))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void consumeSchemeEvents_schemeCreated_routesToUpsertScheme() throws Exception {
        String message = """
                {"eventType":"SCHEME_CREATED","tenantId":7,"schemeId":99,"schemeName":"Test Scheme","status":1}
                """;

        consumer.consumeSchemeEvents(message);

        ArgumentCaptor<SchemeEvent> captor = ArgumentCaptor.forClass(SchemeEvent.class);
        verify(dimensionService).upsertScheme(captor.capture());
        SchemeEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(7);
        assertThat(readField(event, "schemeId")).isEqualTo(99);
        assertThat(readField(event, "schemeName")).isEqualTo("Test Scheme");
        verifyNoInteractions(factService);
    }

    @Test
    void consumeSchemeEvents_lgdLocationCreated_routesToUpsertLgdLocation() throws Exception {
        String message = """
                {"eventType":"LGD_LOCATION_CREATED","tenantId":7,"lgdId":123,"title":"District A","lgdLevel":2}
                """;

        consumer.consumeSchemeEvents(message);

        ArgumentCaptor<LgdLocationEvent> captor = ArgumentCaptor.forClass(LgdLocationEvent.class);
        verify(dimensionService).upsertLgdLocation(captor.capture());
        LgdLocationEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(7);
        assertThat(readField(event, "lgdId")).isEqualTo(123);
        assertThat(readField(event, "title")).isEqualTo("District A");
        verifyNoInteractions(factService);
    }

    @Test
    void consumeSchemeEvents_departmentLocationCreated_routesToUpsertDepartmentLocation() throws Exception {
        String message = """
                {"eventType":"DEPARTMENT_LOCATION_CREATED","tenantId":7,"departmentId":555,"title":"Dept A","departmentLevel":2}
                """;

        consumer.consumeSchemeEvents(message);

        ArgumentCaptor<DepartmentLocationEvent> captor = ArgumentCaptor.forClass(DepartmentLocationEvent.class);
        verify(dimensionService).upsertDepartmentLocation(captor.capture());
        DepartmentLocationEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(7);
        assertThat(readField(event, "departmentId")).isEqualTo(555);
        assertThat(readField(event, "title")).isEqualTo("Dept A");
        verifyNoInteractions(factService);
    }

    @Test
    void consumeSchemeEvents_unknownEventType_isIgnored() {
        String message = """
                {"eventType":"SCHEME_DELETED","schemeId":99}
                """;

        consumer.consumeSchemeEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeSchemeEvents_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeSchemeEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTelemetryEvents_meterReadingRecorded_routesToIngestMeterReading() throws Exception {
        String message = """
                {"eventType":"METER_READING_RECORDED","tenantId":8,"schemeId":10,"userId":2,"extractedReading":123}
                """;

        consumer.consumeTelemetryEvents(message);

        ArgumentCaptor<MeterReadingEvent> captor = ArgumentCaptor.forClass(MeterReadingEvent.class);
        verify(factService).ingestMeterReading(captor.capture());
        MeterReadingEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(8);
        assertThat(readField(event, "schemeId")).isEqualTo(10);
        assertThat(readField(event, "extractedReading")).isEqualTo(123);
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeTelemetryEvents_waterQuantityRecorded_routesToIngestWaterQuantity() throws Exception {
        String message = """
                {"eventType":"WATER_QUANTITY_RECORDED","tenantId":8,"schemeId":10,"userId":2,"waterQuantity":45,"submissionStatus":1,"date":"2026-04-10"}
                """;

        consumer.consumeTelemetryEvents(message);

        ArgumentCaptor<WaterQuantityEvent> captor = ArgumentCaptor.forClass(WaterQuantityEvent.class);
        verify(factService).ingestWaterQuantity(captor.capture());
        WaterQuantityEvent event = captor.getValue();
        assertThat(readField(event, "waterQuantity")).isEqualTo(45);
        assertThat(readField(event, "date")).isEqualTo("2026-04-10");
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeTelemetryEvents_schemePerformanceRecorded_routesToIngestSchemePerformance() throws Exception {
        String message = """
                {"eventType":"SCHEME_PERFORMANCE_RECORDED","tenantId":8,"schemeId":10,"performanceScore":88.25,"lastWaterSupplyDate":"2026-04-09"}
                """;

        consumer.consumeTelemetryEvents(message);

        ArgumentCaptor<SchemePerformanceEvent> captor = ArgumentCaptor.forClass(SchemePerformanceEvent.class);
        verify(factService).ingestSchemePerformance(captor.capture());
        SchemePerformanceEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(8);
        assertThat(readField(event, "schemeId")).isEqualTo(10);
        assertThat(readField(event, "performanceScore")).isNotNull();
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeTelemetryEvents_anomalyRecorded_routesToIngestAnomalyRecorded() throws Exception {
        String message = """
                {"eventType":"ANOMALY_RECORDED","tenantId":8,"schemeId":10,"userId":2,"type":1,"uuid":"11111111-1111-1111-1111-111111111111"}
                """;

        consumer.consumeTelemetryEvents(message);

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(factService).ingestAnomalyRecorded(captor.capture());
        AnomalyEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(8);
        assertThat(readField(event, "uuid")).isEqualTo("11111111-1111-1111-1111-111111111111");
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeTelemetryEvents_unknownEventType_isIgnored() {
        String message = """
                {"eventType":"SOMETHING_ELSE","tenantId":8}
                """;

        consumer.consumeTelemetryEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTelemetryEvents_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeTelemetryEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeAnomalyEvents_escalationCreated_routesToIngestEscalation() throws Exception {
        String message = """
                {"eventType":"ESCALATION_CREATED","tenantId":9,"schemeId":55,"escalationType":1,"message":"Test","userId":12,"resolutionStatus":0}
                """;

        consumer.consumeAnomalyEvents(message);

        ArgumentCaptor<EscalationEvent> captor = ArgumentCaptor.forClass(EscalationEvent.class);
        verify(factService).ingestEscalation(captor.capture());
        EscalationEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(9);
        assertThat(readField(event, "schemeId")).isEqualTo(55);
        assertThat(readField(event, "message")).isEqualTo("Test");
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeAnomalyEvents_unknownEventType_isIgnored() {
        String message = """
                {"eventType":"ESCALATION_DELETED","tenantId":9}
                """;

        consumer.consumeAnomalyEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeAnomalyEvents_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeAnomalyEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeCommonTopic_escalation_routesToIngestTenantEscalation() throws Exception {
        String message = """
                {"eventType":"ESCALATION","tenantId":10,"escalationLevel":2,"anomalyType":"BFM_MISSED","officerPhone":"91XXXXXXXXXX","officerName":"Officer A","operators":[]}
                """;

        consumer.consumeCommonTopic(message);

        ArgumentCaptor<TenantEscalationEvent> captor = ArgumentCaptor.forClass(TenantEscalationEvent.class);
        verify(factService).ingestTenantEscalation(captor.capture());
        TenantEscalationEvent event = captor.getValue();
        assertThat(readField(event, "tenantId")).isEqualTo(10);
        assertThat(readField(event, "escalationLevel")).isEqualTo(2);
        assertThat(readField(event, "anomalyType")).isEqualTo("BFM_MISSED");
        verifyNoInteractions(dimensionService);
    }

    @Test
    void consumeCommonTopic_unknownEventType_isIgnored() {
        String message = """
                {"eventType":"NUDGE","tenantId":10}
                """;

        consumer.consumeCommonTopic(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeCommonTopic_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeCommonTopic(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeUserEvents_invalidJson_isIgnoredAsUnknownEventType() {
        String message = """
                {invalid-json
                """;

        consumer.consumeUserEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTelemetryEvents_knownEventType_butInvalidPayload_throwsRuntimeException() {
        String message = """
                {"eventType":"METER_READING_RECORDED","tenantId":1,"schemeId":"nope"}
                """;

        assertThatThrownBy(() -> consumer.consumeTelemetryEvents(message))
                .isInstanceOf(RuntimeException.class);

        verifyNoMoreInteractions(dimensionService);
        verifyNoMoreInteractions(factService);
    }
}
