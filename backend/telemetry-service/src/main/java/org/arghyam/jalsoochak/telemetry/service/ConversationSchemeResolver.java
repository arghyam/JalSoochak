package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * NUDGE-SCHEME: the scheme today's WhatsApp conversation is about, for every step that follows the
 * operator's pick on {@code /scheme/selected}.
 *
 * <p>In order: the {@code scheme-selection-*} placeholder while it is still empty; once a meter photo
 * has been written onto it, the scheme of the operator's latest WhatsApp row today; and only then the
 * operator's first mapped scheme, which is also the only one for a single-scheme operator (the flow
 * asks only when there are several). Without the middle step, a value typed after a photo was checked
 * against, and written to, the first scheme instead of the one the photo was taken for.
 */
@Component
@RequiredArgsConstructor
public class ConversationSchemeResolver {

    static final String NOT_MAPPED_MESSAGE = "Operator is not mapped to any scheme";

    private final TelemetryTenantRepository telemetryTenantRepository;

    public Optional<Long> resolve(TelemetryOperatorWithSchema operatorWithSchema) {
        String schemaName = operatorWithSchema.schemaName();
        Long operatorId = operatorWithSchema.operator().id();
        LocalDate today = ReadingTime.today();
        return telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(schemaName, operatorId, today)
                .map(TelemetrySchemeSelectionRecord::schemeId)
                .or(() -> telemetryTenantRepository.findSchemeOfLatestWhatsAppRowForDate(schemaName, operatorId, today))
                .or(() -> telemetryTenantRepository.findFirstSchemeForUser(schemaName, operatorId));
    }

    /**
     * @throws IllegalStateException when the operator is mapped to no scheme at all
     */
    public Long require(TelemetryOperatorWithSchema operatorWithSchema) {
        return resolve(operatorWithSchema).orElseThrow(() -> new IllegalStateException(NOT_MAPPED_MESSAGE));
    }
}
