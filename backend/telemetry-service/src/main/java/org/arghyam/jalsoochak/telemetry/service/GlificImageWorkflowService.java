package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GlificImageWorkflowService {
    private static final String OPERATOR_TOKEN = "operator";
    private static final String SCHEME_TOKEN = "scheme";
    private static final String NOT_FOUND_TOKEN = "not found";

    private final GlificMediaService glificMediaService;
    private final BfmReadingService bfmReadingService;
    private final TelemetryTenantRepository telemetryTenantRepository;
    private final GlificOperatorContextService operatorContextService;
    private final GlificLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private final UserChannelPreferenceRepository userChannelPreferenceRepository;
    private final ObjectMapper objectMapper;

    // LENIENT-INGEST: master off-switch for recording submissions with a missing scheme/operator.
    // Set telemetry.lenient-ingestion.enabled=false to restore the original reject behaviour.
    @Value("${telemetry.lenient-ingestion.enabled:true}")
    private boolean lenientIngestionEnabled = true;

    public GlificImageWorkflowService(GlificMediaService glificMediaService,
                                      BfmReadingService bfmReadingService,
                                      TelemetryTenantRepository telemetryTenantRepository,
                                      GlificOperatorContextService operatorContextService,
                                      GlificLocalizationService localizationService,
                                      TenantConfigRepository tenantConfigRepository,
                                      UserChannelPreferenceRepository userChannelPreferenceRepository,
                                      ObjectMapper objectMapper) {
        this.glificMediaService = glificMediaService;
        this.bfmReadingService = bfmReadingService;
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.operatorContextService = operatorContextService;
        this.localizationService = localizationService;
        this.tenantConfigRepository = tenantConfigRepository;
        this.userChannelPreferenceRepository = userChannelPreferenceRepository;
        this.objectMapper = objectMapper;
    }

    public CreateReadingResponse processImage(GlificWebhookRequest glificWebhookRequest) {
        try {
            String contactId = glificWebhookRequest.getContactId();
            String mediaId = glificWebhookRequest.getMediaId();
            String mediaUrl = glificWebhookRequest.getMediaUrl();
            boolean isMeterReplaced = Boolean.TRUE.equals(glificWebhookRequest.getIsMeterReplaced());

            byte[] imageBytes = glificMediaService.downloadImage(mediaId, mediaUrl);
            log.debug("Downloaded image for contactId {} (bytes={})", contactId, imageBytes.length);

            String imageStorageUrl = glificMediaService.uploadImage(contactId, imageBytes);

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId);
            Integer tenantId = operatorWithSchema.operator().tenantId();
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );
//            ensureSelectedChannelExists(tenantId, contactId);

            Long schemeId = telemetryTenantRepository
                    .findLatestPendingSchemeSelectionForDate(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id(),
                            LocalDate.now(ReadingTime.ZONE)
                    )
                    .map(TelemetrySchemeSelectionRecord::schemeId)
                    .or(() -> telemetryTenantRepository.findFirstSchemeForUser(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id()
                    ))
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            CreateReadingRequest createReadingRequest = CreateReadingRequest.builder()
                    .schemeId(schemeId)
                    .operatorId(operatorWithSchema.operator().id())
                    .readingUrl(imageStorageUrl)
                    .readingValue(null)
                    // Record the reason on the flow_reading_table for meter-replacement submissions.
                    .meterChangeReason(isMeterReplaced ? "METER_REPLACED" : null)
                    .readingTime(null)
                    .build();

            CreateReadingResponse response = bfmReadingService.createReading(
                    createReadingRequest,
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator(),
                    contactId,
                    isMeterReplaced,
                    FlowVisionRetryMode.RESILIENT
            );
            response.setMessage(localizationService.localizeMessage(response.getMessage(), languageKey));
            return response;
        } catch (Exception e) {
            log.error("Unexpected error processing image for contactId {}: {}", maskPhone(glificWebhookRequest.getContactId()), e.getMessage(), e);
            if (log.isDebugEnabled()) {
                log.debug("Unexpected error processing image rawContactId={}: {}", glificWebhookRequest.getContactId(), e.getMessage());
            }
            String languageKey = localizationService.resolveLanguageKeyForContact(glificWebhookRequest.getContactId());
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Image could not be processed.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .errorCode(errorCodeForException(e))
                    .correlationId(glificWebhookRequest.getContactId())
                    .build();
        }
    }

    public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
        String safeContactId = request != null ? request.getPhoneNumber() : null;
        try {
            String contactId = safeContactId;
            boolean phoneAbsent = contactId == null || contactId.isBlank();

            // PHONE-OPTIONAL: with a phone the submitter is known, so the operator is resolved first and
            // the scheme is preferred among the ones they are mapped to. Without a phone there is no
            // submitter to start from, so the scheme is the anchor and the operator is inferred from it.
            SubmissionContext context = phoneAbsent
                    ? resolveOperatorFromScheme(request, preferredTenantId)
                    : resolveFromSubmittedPhone(request, contactId, preferredTenantId);

            TelemetryOperatorWithSchema operatorWithSchema = context.operatorWithSchema();
            String schemaName = operatorWithSchema.schemaName();
            TelemetryOperator operator = operatorWithSchema.operator();
            Long operatorId = operator.id();
            Long schemeId = context.schemeId();
            int ingestionSource = context.ingestionSource();

            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, operator.tenantId())
            );

            // SCHEME-ID-MISMATCH: the reading matched on one id (state first, then centre); when it
            // resolved to a real scheme (not an auto-provisioned placeholder) and the request carried
            // both ids, cross-check the other id against our master data and flag the scheme if it
            // disagrees, so suspect ids can be reconciled with the govt dept later. Best-effort only.
            if (!IngestionSource.has(ingestionSource, IngestionSource.UNKNOWN_SCHEME)) {
                telemetryTenantRepository.recordSchemeIdMismatchIfAny(
                        schemaName,
                        schemeId,
                        request.getStateSchemeId(),
                        request.getCentreSchemeId());
            }

            // reading_at/reading_date are stored in IST (see ReadingTime); other columns stay UTC.
            LocalDateTime readingTime = ReadingTime.fromClient(request.getReadingDateTime());

            boolean lenient = ingestionSource != IngestionSource.NORMAL;
            CreateReadingRequest createReadingRequest = CreateReadingRequest.builder()
                    .schemeId(schemeId)
                    .operatorId(operatorId)
                    .readingUrl(request.getReadingUrl())
                    .readingValue(request.getConfirmedReading())
                    .meterChangeReason(null)
                    .readingTime(readingTime)
                    .ingestionSource(lenient ? ingestionSource : null)
                    .submittedStateSchemeId(lenient ? request.getStateSchemeId() : null)
                    .submittedCentreSchemeId(lenient ? request.getCentreSchemeId() : null)
                    .submittedPhoneHash(lenient ? context.submittedPhoneHash() : null)
                    .build();

            if (lenient) {
                // Canonical, greppable audit line for every leniently-recorded submission.
                log.info("assam_reading_lenient_recorded ingestionSource={} unknownScheme={} unknownOperator={} operatorNotMapped={} phoneAbsent={} operatorId={} schemeId={} submittedStateSchemeId={} submittedCentreSchemeId={} submittedPhone={}",
                        ingestionSource,
                        IngestionSource.has(ingestionSource, IngestionSource.UNKNOWN_SCHEME),
                        IngestionSource.has(ingestionSource, IngestionSource.UNKNOWN_OPERATOR),
                        IngestionSource.has(ingestionSource, IngestionSource.OPERATOR_NOT_MAPPED),
                        IngestionSource.has(ingestionSource, IngestionSource.PHONE_ABSENT),
                        operatorId,
                        schemeId,
                        sanitizeSchemeId(request.getStateSchemeId()),
                        sanitizeSchemeId(request.getCentreSchemeId()),
                        maskPhone(contactId));
            }

            // PHONE-OPTIONAL: the contact drives the reading channel lookup (BFM/ELM/PDU/…), which
            // analytics uses to pick the per-channel water-quantity maths. When the submission carried
            // no phone, use the credited operator's own number so the reading is processed with that
            // operator's channel rather than silently falling back to the default one.
            String channelContactId = phoneAbsent ? operator.phoneNumber() : contactId;

            CreateReadingResponse response = bfmReadingService.createReading(
                    createReadingRequest,
                    schemaName,
                    operator,
                    channelContactId,
                    false,
                    FlowVisionRetryMode.RESILIENT
            );

            applyGeolocationIfPresent(request, schemaName, operatorId, response.getCorrelationId());
            response.setMessage(localizationService.localizeMessage(response.getMessage(), languageKey));
            return response;
        } catch (Exception e) {
            log.error("Unexpected error processing Assam reading for contactId {}: {}", maskPhone(safeContactId), e.getMessage(), e);
            if (log.isDebugEnabled()) {
                log.debug("Unexpected error processing Assam reading rawContactId={}: {}", safeContactId, e.getMessage());
            }
            String languageKey = localizationService.resolveLanguageKeyForContact(safeContactId);
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Assam reading could not be processed.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .errorCode(errorCodeForException(e))
                    .correlationId(safeContactId)
                    .build();
        }
    }

    private TelemetryErrorCode errorCodeForException(Exception e) {
        String message = e != null ? e.getMessage() : null;
        if (message == null || message.isBlank()) {
            return TelemetryErrorCode.PROCESSING_FAILED;
        }
        String normalized = message.toLowerCase();
        if (normalized.contains(OPERATOR_TOKEN) && normalized.contains(SCHEME_TOKEN)) {
            return TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME;
        }
        if (normalized.contains(SCHEME_TOKEN) && normalized.contains(NOT_FOUND_TOKEN)) {
            return TelemetryErrorCode.SCHEME_NOT_FOUND;
        }
        if (normalized.contains(OPERATOR_TOKEN) && normalized.contains(NOT_FOUND_TOKEN)) {
            return TelemetryErrorCode.OPERATOR_NOT_FOUND;
        }
        return TelemetryErrorCode.PROCESSING_FAILED;
    }

    // LENIENT-INGEST: result of scheme resolution — the scheme id to record against plus the
    // ingestion-source bits describing why it had to be resolved leniently (0 for the normal path).
    private record SchemeResolution(Long schemeId, int ingestionSourceBits) {
    }

    // PHONE-OPTIONAL: everything a submission needs before a reading row can be written — who it is
    // credited to, which scheme it lands on, and how both had to be resolved.
    private record SubmissionContext(TelemetryOperatorWithSchema operatorWithSchema,
                                     Long schemeId,
                                     int ingestionSource,
                                     String submittedPhoneHash) {
    }

    /**
     * LENIENT-INGEST: resolves operator and scheme for a submission that carried a phone number. The
     * operator comes first — falling back to the tenant's sentinel "Unknown operator" when the phone is
     * not registered so the submission is still recorded — and the scheme is then preferred among the
     * ones that operator is mapped to.
     */
    private SubmissionContext resolveFromSubmittedPhone(AssamReadingRequest request,
                                                        String contactId,
                                                        Integer preferredTenantId) {
        Optional<TelemetryOperatorWithSchema> resolvedOperator =
                operatorContextService.tryResolveOperatorWithSchema(contactId, preferredTenantId);

        int ingestionSource = IngestionSource.NORMAL;
        boolean operatorIsSentinel = false;
        String submittedPhoneHash = null;
        TelemetryOperatorWithSchema operatorWithSchema;

        if (resolvedOperator.isPresent()) {
            operatorWithSchema = resolvedOperator.get();
        } else if (lenientIngestionEnabled) {
            String tenantSchema = telemetryTenantRepository.findSchemaNameByTenantId(preferredTenantId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No operator found and tenant schema could not be resolved for lenient ingestion"));
            operatorWithSchema = resolveSentinelOperator(tenantSchema, preferredTenantId);
            operatorIsSentinel = true;
            ingestionSource |= IngestionSource.UNKNOWN_OPERATOR;
            submittedPhoneHash = telemetryTenantRepository.hashSubmittedPhone(contactId);
            // Scheme ids / user ids are not PII; the raw phone stays at DEBUG only.
            log.info("assam_reading_lenient reason=\"operator_not_found\" sentinelUserId={} submittedPhone={}",
                    operatorWithSchema.operator().id(), maskPhone(contactId));
            if (log.isDebugEnabled()) {
                log.debug("assam_reading_lenient reason=\"operator_not_found\" rawContactId={}", contactId);
            }
        } else {
            // Flag disabled: reproduce the original throwing behaviour (and its message).
            operatorContextService.resolveOperatorWithSchema(contactId, preferredTenantId);
            throw new IllegalStateException("No operator found for the provided contactId");
        }

        SchemeResolution schemeResolution = resolveAssamSchemeLenient(
                operatorWithSchema.schemaName(), operatorWithSchema.operator().id(), operatorIsSentinel,
                request.getStateSchemeId(), request.getCentreSchemeId());

        return new SubmissionContext(
                operatorWithSchema,
                schemeResolution.schemeId(),
                ingestionSource | schemeResolution.ingestionSourceBits(),
                submittedPhoneHash);
    }

    /**
     * PHONE-OPTIONAL: resolves operator and scheme for a submission that carried no phone number.
     * Without a submitter the scheme is the only anchor, so it is resolved first (ignoring operator
     * mappings, which cannot be preferred when no operator is known yet) and the reading is credited to
     * the first pump operator mapped to that scheme. A scheme with no mapped pump operator falls back to
     * the tenant sentinel, exactly as an unregistered phone does. Every submission resolved here is
     * tagged {@link IngestionSource#PHONE_ABSENT} so an inferred operator is never mistaken for the real
     * submitter — including the case where the inferred operator is a perfectly normal, mapped user.
     */
    private SubmissionContext resolveOperatorFromScheme(AssamReadingRequest request, Integer preferredTenantId) {
        String schemaName = telemetryTenantRepository.findSchemaNameByTenantId(preferredTenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No phone number was submitted and the tenant schema could not be resolved"));

        SchemeResolution schemeResolution = resolveAssamSchemeWithoutOperator(
                schemaName, request.getStateSchemeId(), request.getCentreSchemeId());
        int ingestionSource = IngestionSource.PHONE_ABSENT | schemeResolution.ingestionSourceBits();

        Optional<TelemetryOperator> mappedOperator = telemetryTenantRepository
                .findFirstPumpOperatorForScheme(schemaName, schemeResolution.schemeId());
        if (mappedOperator.isPresent()) {
            log.info("assam_reading_phone_absent reason=\"operator_inferred_from_scheme\" operatorId={} schemeId={}",
                    mappedOperator.get().id(), schemeResolution.schemeId());
            return new SubmissionContext(
                    new TelemetryOperatorWithSchema(schemaName, mappedOperator.get()),
                    schemeResolution.schemeId(),
                    ingestionSource,
                    null);
        }

        if (!lenientIngestionEnabled) {
            log.info("Assam reading rejected reason=\"no_operator_mapped_to_scheme\" phoneAbsent=true schemeId={} stateSchemeId={} centreSchemeId={}",
                    schemeResolution.schemeId(),
                    sanitizeSchemeId(request.getStateSchemeId()),
                    sanitizeSchemeId(request.getCentreSchemeId()));
            throw new IllegalStateException("No operator is mapped to the submitted scheme and no phone number was provided");
        }

        TelemetryOperatorWithSchema sentinel = resolveSentinelOperator(schemaName, preferredTenantId);
        log.info("assam_reading_phone_absent reason=\"no_operator_mapped_to_scheme\" sentinelUserId={} schemeId={}",
                sentinel.operator().id(), schemeResolution.schemeId());
        return new SubmissionContext(
                sentinel,
                schemeResolution.schemeId(),
                ingestionSource | IngestionSource.UNKNOWN_OPERATOR,
                null);
    }

    /** LENIENT-INGEST: the tenant's single sentinel "Unknown operator", created on first use. */
    private TelemetryOperatorWithSchema resolveSentinelOperator(String schemaName, Integer tenantId) {
        Long sentinelUserId = telemetryTenantRepository.getOrCreateUnknownOperatorUserId(schemaName, tenantId);
        TelemetryOperator sentinel = telemetryTenantRepository.findOperatorById(schemaName, sentinelUserId)
                .orElseThrow(() -> new IllegalStateException("Sentinel operator could not be resolved"));
        return new TelemetryOperatorWithSchema(schemaName, sentinel);
    }

    /**
     * PHONE-OPTIONAL: scheme resolution for a phone-less submission — the same state-then-centre lookup
     * as {@link #resolveAssamSchemeLenient} minus the operator-mapping preference, which has no meaning
     * before an operator is known. Unknown ids auto-provision a placeholder scheme when lenient
     * ingestion is enabled, and are rejected as before when it is disabled.
     */
    private SchemeResolution resolveAssamSchemeWithoutOperator(String schemaName,
                                                               String stateSchemeId,
                                                               String centreSchemeId) {
        boolean hasStateSchemeId = stateSchemeId != null && !stateSchemeId.isBlank();
        boolean hasCentreSchemeId = centreSchemeId != null && !centreSchemeId.isBlank();

        Optional<Long> stateResolvedSchemeId = hasStateSchemeId
                ? telemetryTenantRepository.findSchemeIdByStateSchemeId(schemaName, stateSchemeId)
                : Optional.empty();
        if (stateResolvedSchemeId.isPresent()) {
            return new SchemeResolution(stateResolvedSchemeId.get(), IngestionSource.NORMAL);
        }

        Optional<Long> centreResolvedSchemeId = hasCentreSchemeId
                ? telemetryTenantRepository.findSchemeIdByCentreSchemeId(schemaName, centreSchemeId)
                : Optional.empty();
        if (centreResolvedSchemeId.isPresent()) {
            return new SchemeResolution(centreResolvedSchemeId.get(), IngestionSource.NORMAL);
        }

        if (!lenientIngestionEnabled) {
            log.info("Assam reading rejected reason=\"scheme_not_found\" phoneAbsent=true stateSchemeId={} centreSchemeId={}",
                    sanitizeSchemeId(stateSchemeId),
                    sanitizeSchemeId(centreSchemeId));
            throw new IllegalStateException("Scheme not found for the provided state or centre scheme id");
        }

        Long placeholderSchemeId = telemetryTenantRepository.getOrCreatePlaceholderScheme(
                schemaName, stateSchemeId, centreSchemeId);
        log.info("assam_reading_lenient reason=\"scheme_not_found\" phoneAbsent=true auto_provisioned_scheme_id={} stateSchemeId={} centreSchemeId={}",
                placeholderSchemeId,
                sanitizeSchemeId(stateSchemeId),
                sanitizeSchemeId(centreSchemeId));
        return new SchemeResolution(placeholderSchemeId, IngestionSource.UNKNOWN_SCHEME);
    }

    /**
     * LENIENT-INGEST: resolves the scheme for an Assam reading. Prefers a scheme the operator is
     * actually mapped to (normal path, bits=0). When that fails and lenient ingestion is enabled it
     * records against the existing (unmapped) scheme, or auto-provisions a placeholder scheme when the
     * scheme id is unknown — tagging each case so it can be filtered later. When lenient ingestion is
     * disabled it preserves the original reject behaviour.
     */
    private SchemeResolution resolveAssamSchemeLenient(String schemaName,
                                                       Long operatorId,
                                                       boolean operatorIsSentinel,
                                                       String stateSchemeId,
                                                       String centreSchemeId) {
        boolean hasStateSchemeId = stateSchemeId != null && !stateSchemeId.isBlank();
        boolean hasCentreSchemeId = centreSchemeId != null && !centreSchemeId.isBlank();

        Optional<Long> stateResolvedSchemeId = hasStateSchemeId
                ? telemetryTenantRepository.findSchemeIdByStateSchemeId(schemaName, stateSchemeId)
                : Optional.empty();
        if (stateResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(schemaName, operatorId, stateResolvedSchemeId.get())) {
            return new SchemeResolution(stateResolvedSchemeId.get(), IngestionSource.NORMAL);
        }

        Optional<Long> centreResolvedSchemeId = hasCentreSchemeId
                ? telemetryTenantRepository.findSchemeIdByCentreSchemeId(schemaName, centreSchemeId)
                : Optional.empty();
        if (centreResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(schemaName, operatorId, centreResolvedSchemeId.get())) {
            return new SchemeResolution(centreResolvedSchemeId.get(), IngestionSource.NORMAL);
        }

        boolean schemeExistsButNotMapped = stateResolvedSchemeId.isPresent() || centreResolvedSchemeId.isPresent();

        if (!lenientIngestionEnabled) {
            // Original behaviour: reject when nothing resolves-and-maps.
            String rejectionReason = schemeExistsButNotMapped ? "operator_not_mapped_to_scheme" : "scheme_not_found";
            log.info("Assam reading rejected reason=\"{}\" operatorId={} stateSchemeId={} stateSchemeFound={} centreSchemeId={} centreSchemeFound={}",
                    rejectionReason,
                    operatorId,
                    sanitizeSchemeId(stateSchemeId),
                    stateResolvedSchemeId.isPresent(),
                    sanitizeSchemeId(centreSchemeId),
                    centreResolvedSchemeId.isPresent());
            throw new IllegalStateException("Operator is not mapped to the provided state or centre scheme");
        }

        // Scheme exists but the operator is not mapped to it -> record against the existing scheme.
        if (schemeExistsButNotMapped) {
            Long existingSchemeId = stateResolvedSchemeId.orElseGet(centreResolvedSchemeId::get);
            // Only flag OPERATOR_NOT_MAPPED for a real operator; a sentinel operator is already flagged
            // via UNKNOWN_OPERATOR and is never expected to be mapped to anything.
            int bits = operatorIsSentinel ? IngestionSource.NORMAL : IngestionSource.OPERATOR_NOT_MAPPED;
            log.info("assam_reading_lenient reason=\"operator_not_mapped_to_scheme\" operatorId={} schemeId={} stateSchemeId={} stateSchemeFound={} centreSchemeId={} centreSchemeFound={}",
                    operatorId,
                    existingSchemeId,
                    sanitizeSchemeId(stateSchemeId),
                    stateResolvedSchemeId.isPresent(),
                    sanitizeSchemeId(centreSchemeId),
                    centreResolvedSchemeId.isPresent());
            return new SchemeResolution(existingSchemeId, bits);
        }

        // Scheme id not in our records at all -> auto-provision a placeholder scheme.
        Long placeholderSchemeId = telemetryTenantRepository.getOrCreatePlaceholderScheme(schemaName, stateSchemeId, centreSchemeId);
        log.info("assam_reading_lenient reason=\"scheme_not_found\" auto_provisioned_scheme_id={} operatorId={} stateSchemeId={} centreSchemeId={}",
                placeholderSchemeId,
                operatorId,
                sanitizeSchemeId(stateSchemeId),
                sanitizeSchemeId(centreSchemeId));
        return new SchemeResolution(placeholderSchemeId, IngestionSource.UNKNOWN_SCHEME);
    }

    private String sanitizeSchemeId(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return "n/a";
        }
        return schemeId.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "n/a";
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    private void applyGeolocationIfPresent(AssamReadingRequest request,
                                           String schemaName,
                                           Long operatorId,
                                           String correlationId) {
        AssamReadingRequest.Geolocation geolocation = request.getGeolocation();
        if (geolocation == null) {
            return;
        }

        validateGeolocation(geolocation);
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }

        Optional<TelemetryReadingRecord> readingOpt = telemetryTenantRepository.findReadingByCorrelationId(schemaName, correlationId);
        if (readingOpt.isEmpty()) {
            return;
        }

        List<BigDecimal> coordinates = geolocation.getCoordinates();
        BigDecimal longitude = coordinates.get(0);
        BigDecimal latitude = coordinates.get(1);
        telemetryTenantRepository.updateReadingLocation(
                schemaName,
                readingOpt.get().id(),
                latitude,
                longitude,
                operatorId
        );
    }

    private void validateGeolocation(AssamReadingRequest.Geolocation geolocation) {
        String type = geolocation.getType();
        if (type != null && !type.isBlank() && !"Point".equalsIgnoreCase(type)) {
            throw new IllegalStateException("geolocation.type must be Point");
        }

        List<BigDecimal> coordinates = geolocation.getCoordinates();
        if (coordinates == null || coordinates.size() != 2) {
            throw new IllegalStateException("geolocation.coordinates must contain [longitude, latitude]");
        }

        BigDecimal longitude = coordinates.get(0);
        BigDecimal latitude = coordinates.get(1);
        if (latitude == null || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalStateException("geolocation latitude must be between -90 and 90");
        }
        if (longitude == null || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalStateException("geolocation longitude must be between -180 and 180");
        }
    }

    private void ensureSelectedChannelExists(Integer tenantId, String contactId) {
        String message = "Selected channel is no longer available. Please make sure you have a channel selected.";
        if (tenantId == null) {
            throw new IllegalStateException(message);
        }

        String selectedChannel = userChannelPreferenceRepository.findChannelValue(tenantId, contactId)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(message));

        List<String> supportedChannels = tenantConfigRepository.findConfigValue(tenantId, "TENANT_SUPPORTED_CHANNELS")
                .map(rawConfig -> parseSupportedChannels(tenantId, rawConfig))
                .orElse(List.of());

        boolean stillAvailable = supportedChannels.stream()
                .map(String::trim)
                .anyMatch(option -> option.equalsIgnoreCase(selectedChannel));
        if (!stillAvailable) {
            throw new IllegalStateException(message);
        }
    }

    private List<String> parseSupportedChannels(Integer tenantId, String rawConfig) {
        try {
            JsonNode node = objectMapper.readTree(rawConfig);
            JsonNode channelsNode = node.has("channels") ? node.get("channels") : node;
            if (channelsNode == null) {
                return List.of();
            }
            if (channelsNode.isArray()) {
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                for (JsonNode child : channelsNode) {
                    if (child != null && child.isTextual()) {
                        String value = child.asText().trim();
                        if (!value.isBlank()) {
                            values.add(value);
                        }
                    }
                }
                return values;
            }
            if (channelsNode.isTextual()) {
                String value = channelsNode.asText().trim();
                return value.isBlank() ? List.of() : List.of(value);
            }
        } catch (Exception e) {
            log.warn("Invalid TENANT_SUPPORTED_CHANNELS JSON for tenantId {}: {}", tenantId, e.getMessage());
        }
        return List.of();
    }
}
