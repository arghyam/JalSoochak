package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class TelemetrySubmissionAuditService {

    private final GlificOperatorContextService operatorContextService;
    private final TelemetryTenantRepository telemetryTenantRepository;

    private final Map<LocalDate, Set<String>> dailyUniqueSubmitters = new ConcurrentHashMap<>();

    public SubmissionAuditSnapshot captureForContact(String contactId) {
        cleanupOldDates();

        String maskedPhone = maskPhone(contactId);
        String uniqueKey = normalizePhone(contactId);
        Long schemeId = null;

        try {
            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId);
            String resolvedPhone = operatorWithSchema.operator().phoneNumber();
            maskedPhone = maskPhone(resolvedPhone);
            uniqueKey = normalizePhone(resolvedPhone);
            schemeId = resolveActiveScheme(operatorWithSchema).orElse(null);
        } catch (Exception ignored) {
            // Best-effort logging only. Controller logs must not depend on audit resolution.
        }

        return snapshot(maskedPhone, schemeId, uniqueKey);
    }

    public SubmissionAuditSnapshot captureForPhoneAndScheme(String phoneNumber, Long schemeId) {
        cleanupOldDates();
        return snapshot(maskPhone(phoneNumber), schemeId, normalizePhone(phoneNumber));
    }

    public SubmissionAuditSnapshot captureForAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
        cleanupOldDates();

        String phoneNumber = request != null ? request.getPhoneNumber() : null;
        String maskedPhone = maskPhone(phoneNumber);
        String uniqueKey = normalizePhone(phoneNumber);
        Long schemeId = null;

        try {
            if (request != null && phoneNumber != null && !phoneNumber.isBlank()) {
                TelemetryOperatorWithSchema operatorWithSchema =
                        operatorContextService.resolveOperatorWithSchema(phoneNumber, preferredTenantId);
                maskedPhone = maskPhone(operatorWithSchema.operator().phoneNumber());
                uniqueKey = normalizePhone(operatorWithSchema.operator().phoneNumber());
                schemeId = resolveAssamSchemeId(
                        operatorWithSchema,
                        request.getStateSchemeId(),
                        request.getCentreSchemeId()
                ).orElse(null);
            }
        } catch (Exception ignored) {
            // Best-effort logging only. Controller logs must not depend on audit resolution.
        }

        return snapshot(maskedPhone, schemeId, uniqueKey);
    }

    private SubmissionAuditSnapshot snapshot(String maskedPhone, Long schemeId, String uniqueKey) {
        LocalDate today = ReadingTime.today();
        int uniqueCount = recordUniqueSubmitter(today, uniqueKey);
        return new SubmissionAuditSnapshot(maskedPhone, schemeId, uniqueCount, today);
    }

    private int recordUniqueSubmitter(LocalDate date, String uniqueKey) {
        String effectiveKey = (uniqueKey == null || uniqueKey.isBlank()) ? "unknown" : uniqueKey;
        Set<String> submitters = dailyUniqueSubmitters.computeIfAbsent(date, ignored -> ConcurrentHashMap.newKeySet());
        submitters.add(effectiveKey);
        return submitters.size();
    }

    private Optional<Long> resolveActiveScheme(TelemetryOperatorWithSchema operatorWithSchema) {
        return telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(
                        operatorWithSchema.schemaName(),
                        operatorWithSchema.operator().id(),
                        ReadingTime.today()
                )
                .map(TelemetrySchemeSelectionRecord::schemeId)
                .or(() -> telemetryTenantRepository.findFirstSchemeForUser(
                        operatorWithSchema.schemaName(),
                        operatorWithSchema.operator().id()
                ));
    }

    private Optional<Long> resolveAssamSchemeId(TelemetryOperatorWithSchema operatorWithSchema,
                                                String stateSchemeId,
                                                String centreSchemeId) {
        Optional<Long> stateResolvedSchemeId = telemetryTenantRepository.findSchemeIdByStateSchemeId(
                operatorWithSchema.schemaName(),
                stateSchemeId
        );
        if (stateResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(
                operatorWithSchema.schemaName(),
                operatorWithSchema.operator().id(),
                stateResolvedSchemeId.get()
        )) {
            return stateResolvedSchemeId;
        }

        Optional<Long> centreResolvedSchemeId = telemetryTenantRepository.findSchemeIdByCentreSchemeId(
                operatorWithSchema.schemaName(),
                centreSchemeId
        );
        if (centreResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(
                operatorWithSchema.schemaName(),
                operatorWithSchema.operator().id(),
                centreResolvedSchemeId.get()
        )) {
            return centreResolvedSchemeId;
        }

        return Optional.empty();
    }

    private void cleanupOldDates() {
        LocalDate today = ReadingTime.today();
        dailyUniqueSubmitters.keySet().removeIf(date -> !today.equals(date));
    }

    private static String normalizePhone(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        return digits.isBlank() ? phoneNumber.trim() : digits;
    }

    private static String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "unknown";
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    public record SubmissionAuditSnapshot(
            String maskedPhone,
            Long schemeId,
            int dailyUniqueUserCount,
            LocalDate date
    ) {
    }
}
