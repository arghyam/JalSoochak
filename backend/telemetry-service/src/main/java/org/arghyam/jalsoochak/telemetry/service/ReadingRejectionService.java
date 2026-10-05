package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The chatbot's "the reading you extracted is wrong" answer, given right after an image reading.
 *
 * <p>The flow used to call the vendor route {@code /readings/reset-latest} for this. That route
 * authenticates with a per-tenant {@code X-Api-Key} the chatbot does not hold, so every call was
 * refused with 401 and the operator was left with no reply. This service is the webhook-token
 * counterpart, answering in the chatbot's {@code {success, message}} shape.
 *
 * <p>It is narrower than the vendor route on purpose: it only rejects a reading taken
 * <em>today</em>. The vendor route resets the operator's latest reading whatever its date, which is
 * right for an operator-console action but not for a chatbot answer that may arrive late or out of
 * order — an operator with no reading today must not be able to zero yesterday's.
 */
@Slf4j
@Service
public class ReadingRejectionService {

    static final String REJECTED_MESSAGE = "Please enter the correct meter reading.";
    static final String NOTHING_TO_REJECT_MESSAGE = "No reading from today was found to correct. Please send a new meter image.";
    static final String FAILURE_MESSAGE = "The reading could not be corrected. Please try again.";

    private final OperatorContextService operatorContextService;
    private final TelemetryTenantRepository telemetryTenantRepository;
    private final BfmReadingService bfmReadingService;
    private final ConversationLocalizationService localizationService;

    public ReadingRejectionService(OperatorContextService operatorContextService,
                                   TelemetryTenantRepository telemetryTenantRepository,
                                   BfmReadingService bfmReadingService,
                                   ConversationLocalizationService localizationService) {
        this.operatorContextService = operatorContextService;
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.bfmReadingService = bfmReadingService;
        this.localizationService = localizationService;
    }

    public IntroResponse rejectTodaysLatestReading(IntroRequest request) {
        String contactId = request != null ? request.getContactId() : null;
        String languageKey = "english";
        try {
            if (contactId == null || contactId.isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId);
            Integer tenantId = operatorWithSchema.operator().tenantId();
            languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            Optional<TelemetryLatestFlowReadingRecord> latest = telemetryTenantRepository
                    .findLatestFlowReadingByOperator(operatorWithSchema.schemaName(), operatorWithSchema.operator().id());
            if (latest.isEmpty() || !ReadingTime.today().equals(latest.get().readingDate())) {
                return IntroResponse.builder()
                        .success(false)
                        .message(localizationService.localizeMessage(NOTHING_TO_REJECT_MESSAGE, languageKey))
                        .build();
            }

            // Scoped to the operator's own tenant, so the vendor route's cross-tenant guard holds too.
            bfmReadingService.resetLatestConfirmedReadingByPhone(contactId, tenantId, ReportingChannel.WHATSAPP);
            return IntroResponse.builder()
                    .success(true)
                    .message(localizationService.localizeMessage(REJECTED_MESSAGE, languageKey))
                    .build();
        } catch (Exception e) {
            log.warn("Rejecting latest reading failed: {}", e.getMessage());
            log.debug("Rejecting latest reading failed for contactId {}", contactId, e);
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.localizeMessage(FAILURE_MESSAGE, languageKey))
                    .build();
        }
    }
}
