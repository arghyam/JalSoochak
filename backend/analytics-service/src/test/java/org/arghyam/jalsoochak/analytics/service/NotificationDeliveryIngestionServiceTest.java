package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.event.NotificationDeliveryEvent;
import org.arghyam.jalsoochak.analytics.exception.MalformedEventException;
import org.arghyam.jalsoochak.analytics.repository.FactNotificationDeliveryRepository;
import org.arghyam.jalsoochak.analytics.repository.FactNotificationDeliveryRepository.NotificationDeliveryFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryIngestionServiceTest {

    @Mock
    private FactNotificationDeliveryRepository repository;

    @InjectMocks
    private NotificationDeliveryIngestionService service;

    private static NotificationDeliveryEvent.NotificationDeliveryEventBuilder event() {
        return NotificationDeliveryEvent.builder()
                .eventType("NOTIFICATION_DELIVERY_UPDATED")
                .notificationUuid("8d0c2f4e-0000-4000-8000-000000000001")
                .statusVersion(3)
                .tenantId(12)
                .messageType("DAILY_REPORT")
                .channel("WHATSAPP")
                .provider("provider-a")
                .userId(4411L)
                .userType("SECTION_OFFICER")
                .dispatchStatus("ACCEPTED")
                .deliveryStatus("DELIVERED")
                .createdAt("2026-10-06T10:30:00Z")
                .dispatchedAt("2026-10-06T10:30:01Z")
                .latencyMs(412)
                .subjectDate("2026-10-05")
                .costAmount(new BigDecimal("0.3"))
                .costCurrency("INR");
    }

    private NotificationDeliveryFact ingestAndCapture(NotificationDeliveryEvent event) {
        when(repository.upsert(any())).thenReturn(true);
        service.ingest(event);
        ArgumentCaptor<NotificationDeliveryFact> captor = ArgumentCaptor.forClass(NotificationDeliveryFact.class);
        verify(repository).upsert(captor.capture());
        return captor.getValue();
    }

    @Test
    void ingest_mapsEveryFieldAndStoresInstantsOnTheIstWallClock() {
        NotificationDeliveryFact fact = ingestAndCapture(event().deliveredAt("2026-10-06T10:30:31Z").build());

        assertThat(fact.notificationUuid()).isEqualTo("8d0c2f4e-0000-4000-8000-000000000001");
        assertThat(fact.statusVersion()).isEqualTo(3);
        assertThat(fact.tenantId()).isEqualTo(12);
        assertThat(fact.messageType()).isEqualTo("DAILY_REPORT");
        assertThat(fact.channel()).isEqualTo("WHATSAPP");
        assertThat(fact.provider()).isEqualTo("provider-a");
        assertThat(fact.userId()).isEqualTo(4411L);
        assertThat(fact.userType()).isEqualTo("SECTION_OFFICER");
        assertThat(fact.dispatchStatus()).isEqualTo("ACCEPTED");
        assertThat(fact.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(fact.createdAtSource()).isEqualTo(LocalDateTime.of(2026, 10, 6, 16, 0, 0));
        assertThat(fact.dispatchedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 16, 0, 1));
        assertThat(fact.deliveredAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 16, 0, 31));
        assertThat(fact.readAt()).isNull();
        assertThat(fact.settledAt()).isNull();
        assertThat(fact.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(fact.subjectDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(fact.latencyMs()).isEqualTo(412);
        assertThat(fact.timeToDeliverS()).isEqualTo(30);
        assertThat(fact.costAmount()).isEqualByComparingTo("0.3");
        assertThat(fact.costCurrency()).isEqualTo("INR");
    }

    @Test
    void ingest_dispatchAtIstMidnight_fallsOnTheNextIstDay() {
        NotificationDeliveryFact fact = ingestAndCapture(event()
                .createdAt("2026-10-05T18:29:00Z")
                .dispatchedAt("2026-10-05T18:30:00Z")
                .build());

        assertThat(fact.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(fact.dispatchedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0, 0));
    }

    @Test
    void ingest_notDispatched_takesTheDayFromCreatedAt() {
        NotificationDeliveryFact fact = ingestAndCapture(event()
                .dispatchStatus("SKIPPED_NO_CONTACT")
                .deliveryStatus("NOT_SENT")
                .createdAt("2026-10-05T18:29:59Z")
                .dispatchedAt(null)
                .build());

        assertThat(fact.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(fact.dispatchedAt()).isNull();
        assertThat(fact.timeToDeliverS()).isNull();
    }

    @Test
    void ingest_withoutADeliveryTime_measuresTimeToDeliverToTheRead() {
        NotificationDeliveryFact fact = ingestAndCapture(event()
                .deliveryStatus("READ")
                .readAt("2026-10-06T10:32:01Z")
                .build());

        assertThat(fact.timeToDeliverS()).isEqualTo(120);
    }

    @Test
    void ingest_neitherDeliveredNorRead_hasNoTimeToDeliver() {
        NotificationDeliveryFact fact = ingestAndCapture(event().deliveryStatus("PENDING").build());

        assertThat(fact.timeToDeliverS()).isNull();
    }

    @Test
    void ingest_providerClockAheadOfOurs_clampsTimeToDeliverAtZero() {
        NotificationDeliveryFact fact = ingestAndCapture(event().deliveredAt("2026-10-06T10:30:00Z").build());

        assertThat(fact.timeToDeliverS()).isZero();
    }

    @Test
    void ingest_platformLevelNotification_keepsTheNullTenant() {
        NotificationDeliveryFact fact = ingestAndCapture(event()
                .tenantId(null)
                .messageType("LOGIN_OTP")
                .channel("EMAIL")
                .userId(null)
                .userType(null)
                .subjectDate(null)
                .costAmount(null)
                .costCurrency(null)
                .build());

        assertThat(fact.tenantId()).isNull();
        assertThat(fact.userId()).isNull();
        assertThat(fact.subjectDate()).isNull();
        assertThat(fact.costAmount()).isNull();
    }

    @Test
    void ingest_withoutAProvider_storesItAsUnknown() {
        NotificationDeliveryFact fact = ingestAndCapture(event().provider(null).build());

        assertThat(fact.provider()).isEqualTo("unknown");
    }

    @Test
    void ingest_staleVersion_isANoOp() {
        when(repository.upsert(any())).thenReturn(false);

        assertThatCode(() -> service.ingest(event().build())).doesNotThrowAnyException();
    }

    @Test
    void ingest_withoutAUuid_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().notificationUuid(" ").build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void ingest_withoutAStatusVersion_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().statusVersion(null).build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void ingest_withoutADeliveryStatus_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().deliveryStatus(null).build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void ingest_withoutCreatedAt_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().createdAt(null).build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void ingest_unparseableInstant_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().deliveredAt("yesterday").build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void ingest_unparseableSubjectDate_isMalformed() {
        assertThatThrownBy(() -> service.ingest(event().subjectDate("05/10/2026").build()))
                .isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repository);
    }
}
