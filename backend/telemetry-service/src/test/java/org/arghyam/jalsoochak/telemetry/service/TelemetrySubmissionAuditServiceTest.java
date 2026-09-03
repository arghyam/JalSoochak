package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The audit snapshot the controllers log alongside each reading submission: a masked phone number,
 * the scheme the submission belongs to, and a per-day distinct-submitter counter.
 *
 * <p>Phone numbers are PII, so the snapshot must never carry more than the last four digits.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelemetrySubmissionAuditService")
class TelemetrySubmissionAuditServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final String PHONE = "919999900001";

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @InjectMocks
    private TelemetrySubmissionAuditService service;

    private static TelemetryOperatorWithSchema operator() {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, 3, "Asha", "asha@example.org", PHONE, 1));
    }

    @Nested
    @DisplayName("phone masking")
    class PhoneMasking {

        @Test
        void masksAllButTheLastFourDigits() {
            var snapshot = service.captureForPhoneAndScheme(PHONE, 7L);

            assertThat(snapshot.maskedPhone()).isEqualTo("****0001");
            assertThat(snapshot.maskedPhone()).doesNotContain("9999990");
        }

        @Test
        void reportsUnknownForAMissingPhoneNumber() {
            assertThat(service.captureForPhoneAndScheme(null, 7L).maskedPhone()).isEqualTo("unknown");
            assertThat(service.captureForPhoneAndScheme("   ", 7L).maskedPhone()).isEqualTo("unknown");
        }

        @Test
        void fullyMasksAShortNumberRatherThanLeakingIt() {
            assertThat(service.captureForPhoneAndScheme("1234", 7L).maskedPhone()).isEqualTo("****");
            assertThat(service.captureForPhoneAndScheme("12", 7L).maskedPhone()).isEqualTo("****");
        }

        @Test
        void stripsFormattingBeforeMasking() {
            assertThat(service.captureForPhoneAndScheme("+91 99999-00002", 7L).maskedPhone())
                    .isEqualTo("****0002");
        }
    }

    @Nested
    @DisplayName("daily unique submitter counter")
    class UniqueSubmitters {

        @Test
        void countsEachDistinctPhoneNumberOnce() {
            assertThat(service.captureForPhoneAndScheme(PHONE, 7L).dailyUniqueUserCount()).isEqualTo(1);
            assertThat(service.captureForPhoneAndScheme(PHONE, 7L).dailyUniqueUserCount()).isEqualTo(1);
            assertThat(service.captureForPhoneAndScheme("919999900002", 7L).dailyUniqueUserCount()).isEqualTo(2);
        }

        @Test
        void treatsDifferentFormattingsOfOneNumberAsTheSameSubmitter() {
            service.captureForPhoneAndScheme("919999900003", 7L);

            assertThat(service.captureForPhoneAndScheme("+91 99999-00003", 7L).dailyUniqueUserCount())
                    .isEqualTo(1);
        }

        @Test
        void groupsAllUnidentifiedSubmittersUnderASingleBucket() {
            service.captureForPhoneAndScheme(null, 7L);

            assertThat(service.captureForPhoneAndScheme("   ", 7L).dailyUniqueUserCount()).isEqualTo(1);
        }

        @Test
        void stampsTheSnapshotWithTodaysDate() {
            assertThat(service.captureForPhoneAndScheme(PHONE, 7L).date())
                    .isEqualTo(org.arghyam.jalsoochak.telemetry.util.ReadingTime.today());
        }
    }

    @Nested
    @DisplayName("captureForContact")
    class CaptureForContact {

        @Test
        void prefersTodaysPendingSchemeSelection() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator());
            when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(
                    anyString(), anyLong(), any(LocalDate.class)))
                    .thenReturn(Optional.of(new TelemetrySchemeSelectionRecord(1L, 55L, "corr")));

            assertThat(service.captureForContact(PHONE).schemeId()).isEqualTo(55L);
        }

        @Test
        void fallsBackToTheOperatorsFirstMappedScheme() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator());
            when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(
                    anyString(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
            when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L))
                    .thenReturn(Optional.of(66L));

            assertThat(service.captureForContact(PHONE).schemeId()).isEqualTo(66L);
        }

        @Test
        void reportsANullSchemeWhenTheOperatorHasNoMapping() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator());
            when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(
                    anyString(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
            when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L)).thenReturn(Optional.empty());

            assertThat(service.captureForContact(PHONE).schemeId()).isNull();
        }

        @Test
        void degradesGracefullyWhenOperatorResolutionFails() {
            // Audit is best-effort: the controller's own logging must not depend on it.
            when(operatorContextService.resolveOperatorWithSchema(anyString()))
                    .thenThrow(new IllegalStateException("No operator found"));

            var snapshot = service.captureForContact(PHONE);

            assertThat(snapshot.schemeId()).isNull();
            assertThat(snapshot.maskedPhone()).isEqualTo("****0001");
        }

        @Test
        void usesTheResolvedOperatorPhoneRatherThanTheSubmittedContactId() {
            when(operatorContextService.resolveOperatorWithSchema("abc")).thenReturn(operator());
            when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(
                    anyString(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
            when(telemetryTenantRepository.findFirstSchemeForUser(anyString(), anyLong()))
                    .thenReturn(Optional.empty());

            assertThat(service.captureForContact("abc").maskedPhone()).isEqualTo("****0001");
        }
    }

    @Nested
    @DisplayName("captureForAssamReading")
    class CaptureForAssamReading {

        private AssamReadingRequest request(String phone, String stateSchemeId, String centreSchemeId) {
            AssamReadingRequest request = new AssamReadingRequest();
            request.setPhoneNumber(phone);
            request.setStateSchemeId(stateSchemeId);
            request.setCentreSchemeId(centreSchemeId);
            return request;
        }

        @Test
        void handlesAMissingRequest() {
            var snapshot = service.captureForAssamReading(null, 3);

            assertThat(snapshot.maskedPhone()).isEqualTo("unknown");
            assertThat(snapshot.schemeId()).isNull();
        }

        @Test
        void skipsResolutionForABlankPhoneNumber() {
            var snapshot = service.captureForAssamReading(request("  ", "S-1", "C-1"), 3);

            assertThat(snapshot.maskedPhone()).isEqualTo("unknown");
            assertThat(snapshot.schemeId()).isNull();
        }

        @Test
        void resolvesTheSchemeFromTheStateSchemeIdWhenTheOperatorIsMappedToIt() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE, 3)).thenReturn(operator());
            when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1"))
                    .thenReturn(Optional.of(70L));
            when(telemetryTenantRepository.isOperatorMappedToScheme(SCHEMA, 11L, 70L)).thenReturn(true);

            assertThat(service.captureForAssamReading(request(PHONE, "S-1", "C-1"), 3).schemeId())
                    .isEqualTo(70L);
        }

        @Test
        void fallsBackToTheCentreSchemeIdWhenTheOperatorIsNotMappedToTheStateScheme() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE, 3)).thenReturn(operator());
            when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1"))
                    .thenReturn(Optional.of(70L));
            when(telemetryTenantRepository.isOperatorMappedToScheme(SCHEMA, 11L, 70L)).thenReturn(false);
            when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, "C-1"))
                    .thenReturn(Optional.of(71L));
            when(telemetryTenantRepository.isOperatorMappedToScheme(SCHEMA, 11L, 71L)).thenReturn(true);

            assertThat(service.captureForAssamReading(request(PHONE, "S-1", "C-1"), 3).schemeId())
                    .isEqualTo(71L);
        }

        @Test
        void reportsNoSchemeWhenTheOperatorIsMappedToNeitherId() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE, 3)).thenReturn(operator());
            when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1"))
                    .thenReturn(Optional.of(70L));
            when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, "C-1"))
                    .thenReturn(Optional.of(71L));
            when(telemetryTenantRepository.isOperatorMappedToScheme(anyString(), anyLong(), anyLong()))
                    .thenReturn(false);

            assertThat(service.captureForAssamReading(request(PHONE, "S-1", "C-1"), 3).schemeId()).isNull();
        }

        @Test
        void reportsNoSchemeWhenNeitherSubmittedIdResolves() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE, 3)).thenReturn(operator());
            when(telemetryTenantRepository.findSchemeIdByStateSchemeId(anyString(), any()))
                    .thenReturn(Optional.empty());
            when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(anyString(), any()))
                    .thenReturn(Optional.empty());

            assertThat(service.captureForAssamReading(request(PHONE, "S-1", "C-1"), 3).schemeId()).isNull();
        }

        @Test
        void degradesGracefullyWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(anyString(), any()))
                    .thenThrow(new IllegalStateException("No operator found"));

            var snapshot = service.captureForAssamReading(request(PHONE, "S-1", "C-1"), 3);

            assertThat(snapshot.schemeId()).isNull();
            assertThat(snapshot.maskedPhone()).isEqualTo("****0001");
        }
    }
}
