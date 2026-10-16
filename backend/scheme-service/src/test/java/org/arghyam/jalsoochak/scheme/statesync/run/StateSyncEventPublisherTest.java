package org.arghyam.jalsoochak.scheme.statesync.run;

import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.SchemeReconciler.Reassignment;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncEventPublisher.PendingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the publisher sends, under which key, in which order, and what it reports as not sent. */
@ExtendWith(MockitoExtension.class)
class StateSyncEventPublisherTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 7;

    @Mock
    private KafkaProducer kafka;
    @Mock
    private SchemeService schemeService;
    private StateSyncEventPublisher publisher;

    @BeforeEach
    void setUp() {
        lenient().when(kafka.publishJson(anyString(), anyString(), any())).thenReturn(true);
        lenient().when(schemeService.publishSchemeMappingsReplaced(anyString(), anyInt(), anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());
        publisher = new StateSyncEventPublisher(kafka, schemeService);
    }

    private static PendingEvents schemesAndMoves(List<Integer> schemeIds, List<Reassignment> moves) {
        return new PendingEvents(schemeIds, List.of(), List.of(), moves);
    }

    @Test
    void sendsSchemesOnceEachThroughTheSchemeService() {
        StateSyncEventPublisher.Outcome outcome = publisher.publish(SCHEMA, TENANT_ID, schemesAndMoves(List.of(10, 11, 10), List.of()));

        verify(schemeService).publishSchemeMappingsReplaced(SCHEMA, TENANT_ID, List.of(10, 11));
        assertThat(outcome.failures()).isZero();
    }

    @Test
    void schemesKafkaDidNotAcceptCountAsFailures() {
        when(schemeService.publishSchemeMappingsReplaced(anyString(), anyInt(), anyList())).thenReturn(1);

        assertThat(publisher.publish(SCHEMA, TENANT_ID, schemesAndMoves(List.of(10, 11, 12), List.of())).failures())
                .isEqualTo(2);
    }

    @Test
    void sendsNoSchemeMessageWhenNoSchemeChanged() {
        publisher.publish(SCHEMA, TENANT_ID, PendingEvents.empty());

        verify(schemeService, never()).publishSchemeMappingsReplaced(anyString(), anyInt(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void announcesTheRealSchemeBeforeTheReassignmentAndUnderItsKey() {
        publisher.publish(SCHEMA, TENANT_ID, schemesAndMoves(List.of(10), List.of(new Reassignment(99, 10))));

        InOrder order = inOrder(schemeService, kafka);
        order.verify(schemeService).publishSchemeMappingsReplaced(SCHEMA, TENANT_ID, List.of(10));
        ArgumentCaptor<Object> moved = ArgumentCaptor.forClass(Object.class);
        order.verify(kafka).publishJson(eq("scheme-service-topic"), eq(TENANT_ID + ":10"), moved.capture());
        assertThat((Map<String, Object>) moved.getValue()).containsEntry("eventType", "SCHEME_READINGS_REASSIGNED")
                .containsEntry("fromSchemeId", 99).containsEntry("toSchemeId", 10).containsEntry("tenantId", TENANT_ID);
    }

    @Test
    void returnsTheReassignmentsKafkaDidNotAccept() {
        when(kafka.publishJson(eq("scheme-service-topic"), anyString(),
                argThat(e -> "SCHEME_READINGS_REASSIGNED".equals(((Map<?, ?>) e).get("eventType"))))).thenReturn(false);

        StateSyncEventPublisher.Outcome outcome = publisher.publish(SCHEMA, TENANT_ID,
                schemesAndMoves(List.of(), List.of(new Reassignment(99, 10), new Reassignment(98, 11))));

        assertThat(outcome.failures()).isEqualTo(2);
        assertThat(outcome.failedReassignments()).containsExactly(new Reassignment(99, 10), new Reassignment(98, 11));
    }

    @Test
    void keysUserAndDepartmentEventsByTheirOwnIds() {
        publisher.publish(SCHEMA, TENANT_ID, new PendingEvents(List.of(),
                List.of(Map.of("eventType", "USER_UPDATED", "userId", 5)),
                List.of(Map.of("eventType", "DEPARTMENT_LOCATION_UPDATED", "departmentId", 3)), List.of()));

        verify(kafka).publishJson(eq("user-service-topic"), eq(TENANT_ID + ":user:5"), any());
        verify(kafka).publishJson(eq("scheme-service-topic"), eq(TENANT_ID + ":department:3"), any());
    }
}
