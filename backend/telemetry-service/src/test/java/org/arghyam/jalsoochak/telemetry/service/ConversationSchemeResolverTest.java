package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NUDGE-SCHEME: which scheme a WhatsApp conversation's later steps belong to. The operator here is
 * mapped to {@link #FIRST} and {@link #SELECTED}, and picked {@link #SELECTED}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSchemeResolver")
class ConversationSchemeResolverTest {

    private static final String SCHEMA = "tenant_test";
    private static final long OPERATOR = 1L;
    private static final long FIRST = 10L;
    private static final long SELECTED = 20L;

    private static final TelemetryOperatorWithSchema OPERATOR_WITH_SCHEMA = new TelemetryOperatorWithSchema(
            SCHEMA, new TelemetryOperator(OPERATOR, 1, "op", "op@example.com", "91XXXXXXXXXX", null));

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @InjectMocks
    private ConversationSchemeResolver resolver;

    @Test
    void usesTheSelectionPlaceholderWhileItIsStillEmpty() {
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.of(new TelemetrySchemeSelectionRecord(5L, SELECTED, "scheme-selection-x")));

        assertThat(resolver.resolve(OPERATOR_WITH_SCHEMA)).contains(SELECTED);
        verify(telemetryTenantRepository, never()).findSchemeOfLatestWhatsAppRowForDate(anyString(), anyLong(), any());
        verify(telemetryTenantRepository, never()).findFirstSchemeForUser(anyString(), anyLong());
    }

    @Test
    void afterAPhotoHasFilledThePlaceholderUsesTheSchemeOfTheLatestWhatsAppRow() {
        // The photo was written onto the placeholder, so it no longer reads as a pending selection.
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeOfLatestWhatsAppRowForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.of(SELECTED));

        assertThat(resolver.resolve(OPERATOR_WITH_SCHEMA)).contains(SELECTED);
        verify(telemetryTenantRepository, never()).findFirstSchemeForUser(anyString(), anyLong());
    }

    @Test
    void fallsBackToTheFirstMappedSchemeWhenNothingWasRecordedToday() {
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeOfLatestWhatsAppRowForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, OPERATOR)).thenReturn(Optional.of(FIRST));

        assertThat(resolver.resolve(OPERATOR_WITH_SCHEMA)).contains(FIRST);
        assertThat(resolver.require(OPERATOR_WITH_SCHEMA)).isEqualTo(FIRST);
    }

    @Test
    void looksAtTodayInIst() {
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, OPERATOR)).thenReturn(Optional.of(FIRST));

        resolver.resolve(OPERATOR_WITH_SCHEMA);

        verify(telemetryTenantRepository).findLatestPendingSchemeSelectionForDate(SCHEMA, OPERATOR, ReadingTime.today());
        verify(telemetryTenantRepository).findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, ReadingTime.today());
    }

    @Test
    void anOperatorMappedToNoSchemeHasNone() {
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, OPERATOR)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(OPERATOR_WITH_SCHEMA)).isEmpty();
        // The text is matched by ConversationLocalizationService to give the operator a localised reply.
        assertThatThrownBy(() -> resolver.require(OPERATOR_WITH_SCHEMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operator is not mapped to any scheme");
    }
}
