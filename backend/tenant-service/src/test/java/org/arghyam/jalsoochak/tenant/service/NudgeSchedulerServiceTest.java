package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.NudgeEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NudgeSchedulerService} business logic.
 *
 * <p>Each test calls {@code processNudgesForTenant} directly; tenant iteration
 * is handled by the external CronJob and is not exercised here.</p>
 */
@ExtendWith(MockitoExtension.class)
class NudgeSchedulerServiceTest {

    @Mock
    private NudgeRepository nudgeRepository;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private NudgeSchedulerService nudgeSchedulerService;

    private static final String SCHEMA = "tenant_mp";
    private static final int TENANT_ID = 1;


    @Test
    void processNudgesForTenant_publishesNudgeEvent_forOperatorWithoutUploadToday() {
        stubStream(SCHEMA, Map.of(
                "phone_number", "919876543210",
                "name", "Ramesh Kumar",
                "user_id", 77,
                "pending_scheme_count", 3,
                "language_id", 1
        ));

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        ArgumentCaptor<NudgeEvent> eventCaptor = ArgumentCaptor.forClass(NudgeEvent.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), eq("1:77"), eventCaptor.capture());

        NudgeEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("NUDGE");
        assertThat(event.getRecipientPhone()).isEqualTo("919876543210");
        assertThat(event.getOperatorName()).isEqualTo("Ramesh Kumar");
        assertThat(event.getUserId()).isEqualTo(77L);
        assertThat(event.getPendingSchemeCount()).isEqualTo(3);
        assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(event.getLanguageId()).isEqualTo(1);
        assertThat(event.getNudgeDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
    }

    @Test
    void processNudgesForTenant_queriesTheIstCalendarDay() {
        LocalDate istToday = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        verify(nudgeRepository).streamUsersWithNoUploadToday(eq(SCHEMA), eq(istToday), anyInt(), any());
    }

    @Test
    void processNudgesForTenant_skipsOperator_withBlankPhoneAndNoWhatsappId() {
        Map<String, Object> row = new HashMap<>();
        row.put("phone_number", "");
        row.put("name", "No Phone");
        row.put("user_id", 1);
        row.put("language_id", 0);
        row.put("whatsapp_connection_id", null);

        stubStream(SCHEMA, row);

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        verifyNoInteractions(kafkaProducer);
    }

    @Test
    void processNudgesForTenant_skipsOperator_withNullPhoneAndZeroWhatsappId() {
        Map<String, Object> row = new HashMap<>();
        row.put("phone_number", null);
        row.put("name", "Null Phone");
        row.put("user_id", 1);
        row.put("language_id", 0);
        row.put("whatsapp_connection_id", 0);

        stubStream(SCHEMA, row);

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        verifyNoInteractions(kafkaProducer);
    }

    @Test
    void processNudgesForTenant_publishesEvent_whenPhoneBlankButWhatsappConnectionIdPresent() {
        Map<String, Object> row = new HashMap<>();
        row.put("phone_number", "");
        row.put("name", "No Phone Op");
        row.put("language_id", 2);
        row.put("user_id", 55);
        row.put("whatsapp_connection_id", 123L);

        stubStream(SCHEMA, row);

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        ArgumentCaptor<NudgeEvent> captor = ArgumentCaptor.forClass(NudgeEvent.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), anyString(), captor.capture());
        assertThat(captor.getValue().getWhatsappConnectionId()).isEqualTo(123L);
    }

    @Test
    void processNudgesForTenant_publishesMultipleEvents_forMultipleOperators() {
        stubStream(SCHEMA,
                Map.of("phone_number", "911111111111", "name", "Op A", "user_id", 1, "language_id", 0),
                Map.of("phone_number", "912222222222", "name", "Op B", "user_id", 2, "language_id", 0)
        );

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        verify(kafkaProducer, times(2)).publishJson(eq("common-topic"), anyString(), any(NudgeEvent.class));
    }

    @Test
    void processNudgesForTenant_usesDefaultLanguageId_whenLanguageIdNull() {
        Map<String, Object> rowNullLang = new HashMap<>();
        rowNullLang.put("phone_number", "919000000000");
        rowNullLang.put("name", "No Lang Op");
        rowNullLang.put("user_id", 99);
        rowNullLang.put("language_id", null);

        stubStream(SCHEMA, rowNullLang);

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        ArgumentCaptor<NudgeEvent> captor = ArgumentCaptor.forClass(NudgeEvent.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), anyString(), captor.capture());
        assertThat(captor.getValue().getLanguageId()).isEqualTo(0);
    }

    // ── isolation / security tests ───────────────────────────────────────────────

    @Test
    void processNudgesForTenant_queriesOnlyItsOwnSchema() {
        stubStream(SCHEMA, Map.of("phone_number", "919876543210", "name", "Ramesh", "user_id", 1, "language_id", 0));

        nudgeSchedulerService.processNudgesForTenant(SCHEMA, TENANT_ID);

        // Repository must be called with exactly the given schema — no other schema
        verify(nudgeRepository).streamUsersWithNoUploadToday(eq(SCHEMA), any(LocalDate.class), anyInt(), any());
        verifyNoMoreInteractions(nudgeRepository);
    }

    @Test
    void processNudgesForTenant_twoTenants_eventsCarryCorrectTenantIdAndSchema() {
        String schemaA = "tenant_mp";
        int tenantA = 1;
        String schemaB = "tenant_up";
        int tenantB = 2;

        stubStream(schemaA, Map.of("phone_number", "911111111111", "name", "Op A", "user_id", 1, "language_id", 0));
        stubStream(schemaB, Map.of("phone_number", "912222222222", "name", "Op B", "user_id", 2, "language_id", 0));

        nudgeSchedulerService.processNudgesForTenant(schemaA, tenantA);
        nudgeSchedulerService.processNudgesForTenant(schemaB, tenantB);

        ArgumentCaptor<NudgeEvent> captor = ArgumentCaptor.forClass(NudgeEvent.class);
        verify(kafkaProducer, times(2)).publishJson(eq("common-topic"), anyString(), captor.capture());
        List<NudgeEvent> events = captor.getAllValues();

        // Each event carries its own tenant's id and schema
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getTenantId()).isEqualTo(tenantA);
            assertThat(e.getTenantSchema()).isEqualTo(schemaA);
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getTenantId()).isEqualTo(tenantB);
            assertThat(e.getTenantSchema()).isEqualTo(schemaB);
        });
        // No event should have a mismatched tenantId / tenantSchema pair
        assertThat(events).noneMatch(e -> e.getTenantId() == tenantA && schemaB.equals(e.getTenantSchema()));
        assertThat(events).noneMatch(e -> e.getTenantId() == tenantB && schemaA.equals(e.getTenantSchema()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    @SafeVarargs
    private void stubStream(String schema, Map<String, Object>... rows) {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Map<String, Object>> consumer = inv.getArgument(3);
            for (Map<String, Object> row : rows) {
                consumer.accept(row);
            }
            return rows.length;
        }).when(nudgeRepository).streamUsersWithNoUploadToday(eq(schema), any(LocalDate.class), anyInt(), any());
    }
}
