package org.arghyam.jalsoochak.telemetry.service;

/**
 * The code of every piece of chatbot reply copy kept in the
 * {@code i18n/conversation-messages_<locale>.properties} bundles, rendered through
 * {@link ConversationMessageResolver}.
 *
 * <p>{@code conversation-messages_en} holds an entry for every constant, and no other keys; a
 * translation bundle holds any subset of them.
 */
public enum ConversationMessageCode {

    // ── Allowlisted errors: ConversationLocalizationService.USER_FACING_RULES ───────────────────────
    DUPLICATE_IMAGE("error.duplicateImage"),
    MANUAL_READING_REQUIRED("error.manualReadingRequired"),
    MANUAL_READING_NOT_NUMERIC("error.manualReadingNotNumeric"),
    MANUAL_READING_NOT_POSITIVE("error.manualReadingNotPositive"),
    READING_REQUIRED("error.readingRequired"),
    READING_NOT_NUMERIC("error.readingNotNumeric"),
    READING_NOT_POSITIVE("error.readingNotPositive"),
    READING_NEGATIVE("error.readingNegative"),
    READING_OR_IMAGE_REQUIRED("error.readingOrImageRequired"),
    LANGUAGE_SELECTION_REQUIRED("error.languageSelectionRequired"),
    LANGUAGE_SELECTION_INVALID("error.languageSelectionInvalid"),
    LANGUAGE_OPTIONS_NOT_CONFIGURED("error.languageOptionsNotConfigured"),
    CHANNEL_SELECTION_REQUIRED("error.channelSelectionRequired"),
    CHANNEL_SELECTION_INVALID("error.channelSelectionInvalid"),
    CHANNEL_OPTIONS_NOT_CONFIGURED("error.channelOptionsNotConfigured"),
    CHANNEL_NO_LONGER_AVAILABLE("error.channelNoLongerAvailable"),
    ITEM_SELECTION_REQUIRED("error.itemSelectionRequired"),
    ITEM_SELECTION_INVALID("error.itemSelectionInvalid"),
    ITEM_OPTIONS_NOT_CONFIGURED("error.itemOptionsNotConfigured"),
    SCHEME_SELECTION_REQUIRED("error.schemeSelectionRequired"),
    SCHEME_SELECTION_INVALID("error.schemeSelectionInvalid"),
    METER_CHANGE_REASON_REQUIRED("error.meterChangeReasonRequired"),
    METER_CHANGE_REASON_INVALID("error.meterChangeReasonInvalid"),
    METER_CHANGE_REASONS_NOT_CONFIGURED("error.meterChangeReasonsNotConfigured"),
    SUBMISSION_REASONS_NOT_CONFIGURED("error.submissionReasonsNotConfigured"),
    SCHEME_NOT_FOUND_FOR_ID("error.schemeNotFoundForId"),
    NO_OPERATOR_FOR_SCHEME("error.noOperatorForScheme"),
    NO_SCHEME_FOR_OPERATOR("error.noSchemeForOperator"),
    OPERATOR_NOT_MAPPED_TO_PROVIDED_SCHEME("error.operatorNotMappedToProvidedScheme"),
    OPERATOR_NOT_MAPPED_TO_SELECTED_SCHEME("error.operatorNotMappedToSelectedScheme"),
    SCHEME_NOT_FOUND("error.schemeNotFound"),
    SCHEME_NOT_AUTHORIZED("error.schemeNotAuthorized"),
    SCHEME_ID_NOT_POSITIVE("error.schemeIdNotPositive"),
    OPERATOR_NOT_RESOLVED("error.operatorNotResolved"),
    CONTACT_NOT_IDENTIFIED("error.contactNotIdentified"),
    PHONE_NUMBER_REQUIRED("error.phoneNumberRequired"),
    REQUEST_REFERENCE_MISSING("error.requestReferenceMissing"),
    INVALID_MEDIA("error.invalidMedia"),
    IMAGE_DOWNLOAD_FAILED("error.imageDownloadFailed"),
    ISSUE_REASON_INVALID_CHARACTERS("error.issueReasonInvalidCharacters"),
    ISSUE_REASON_REQUIRED("error.issueReasonRequired"),
    LOCATION_INVALID("error.locationInvalid"),
    NO_READING_FOR_OPERATOR("error.noReadingForOperator"),
    READING_NOT_FOUND("error.readingNotFound"),
    TARGET_READING_DATE_MISSING("error.targetReadingDateMissing"),
    INVALID_API_KEY("error.invalidApiKey"),

    // ── A caller's own fallback, for an error the allowlist does not recognise ─────────────────────
    CHANNEL_SELECTION_NOT_SAVED("failure.channelSelectionNotSaved"),
    IMAGE_NOT_PROCESSED("failure.imageNotProcessed"),
    ISSUE_REPORT_NOT_SAVED("failure.issueReportNotSaved"),
    ITEM_SELECTION_NOT_PREPARED("failure.itemSelectionNotPrepared"),
    ITEM_SELECTION_NOT_SAVED("failure.itemSelectionNotSaved"),
    LANGUAGE_SELECTION_NOT_SAVED("failure.languageSelectionNotSaved"),
    LOCATION_NOT_SAVED("failure.locationNotSaved"),
    MANUAL_READING_NOT_SAVED("failure.manualReadingNotSaved"),
    PREVIOUS_READING_NOT_UPDATED("failure.previousReadingNotUpdated"),
    READING_NOT_PROCESSED("failure.readingNotProcessed"),
    SCHEME_SELECTION_NOT_PREPARED("failure.schemeSelectionNotPrepared"),
    SCHEME_SELECTION_NOT_SAVED("failure.schemeSelectionNotSaved"),

    // ── Conversation copy ───────────────────────────────────────────────────────────────────────────
    WELCOME("conversation.welcome"),
    ISSUE_PROMPT("conversation.issuePrompt"),
    OTHERS_PROMPT("conversation.othersPrompt"),
    TELEMETRY_ISSUE_PROMPT("conversation.telemetryIssuePrompt"),
    METER_CHANGE_PROMPT("conversation.meterChangePrompt"),
    ISSUE_REPORTED("conversation.issueReported"),
    METER_CHANGE_SELECTED("conversation.meterChangeSelected"),
    LOCATION_SAVED("conversation.locationSaved"),
    LOCATION_BOUNDARY_WARNING("conversation.locationBoundaryWarning"),
    READING_ABOVE_SCHEME_MAXIMUM("conversation.readingAboveSchemeMaximum"),
    NO_PREVIOUS_READING("conversation.noPreviousReading"),
    READING_REJECTED("conversation.readingRejected"),
    NOTHING_TO_REJECT("conversation.nothingToReject"),
    REJECTION_FAILED("conversation.rejectionFailed"),

    // ── Built-in issue reasons, and the default reason names recognised in tenant config ───────────
    ISSUE_REASON_METER_REPLACED("reason.meterReplaced"),
    ISSUE_REASON_METER_NOT_WORKING("reason.meterNotWorking"),
    ISSUE_REASON_METER_DAMAGE("reason.meterDamage"),
    ISSUE_REASON_METER_DAMAGED("reason.meterDamaged"),
    ISSUE_REASON_INCORRECT_PREVIOUS_READING("reason.incorrectPreviousReading"),
    ISSUE_REASON_NO_WATER_SUPPLY("reason.noWaterSupply"),
    ISSUE_REASON_NO_READING_SUBMISSION("reason.noReadingSubmission"),
    ISSUE_REASON_OTHERS("reason.others"),
    SUPPLY_REASON_ELECTRICITY_DISCONNECTED("reason.electricityDisconnected"),
    SUPPLY_REASON_NO_ELECTRICITY_TODAY("reason.noElectricityToday"),
    SUPPLY_REASON_PUMP_FAILURE("reason.pumpFailure"),
    SUPPLY_REASON_PIPELINE_BREAK("reason.pipelineBreak"),
    SUPPLY_REASON_WATER_QUALITY("reason.waterQuality"),
    SUPPLY_REASON_SOURCE_DRYING("reason.sourceDrying"),
    SUPPLY_REASON_NATURAL_CALAMITY("reason.naturalCalamity"),

    // ── English written outside the chatbot, recognised by ConversationLocalizationService ─────────
    READING_BELOW_PREVIOUS("recognised.readingBelowPrevious"),
    READING_BELOW_PREVIOUS_WITH_VALUES("recognised.readingBelowPreviousWithValues"),
    LANGUAGE_SELECTED_LABEL("recognised.languageSelectedLabel"),
    READING_CAPTURED("recognised.readingCaptured"),
    READING_CAPTURED_WITH_VALUE("recognised.readingCapturedWithValue"),
    LOW_OCR_CONFIDENCE("recognised.lowOcrConfidence"),
    LOW_OCR_CONFIDENCE_WITH_VALUE("recognised.lowOcrConfidenceWithValue"),
    INVALID_READING_VALUE("recognised.invalidReadingValue"),
    NO_METER_IN_PHOTO("recognised.noMeterInPhoto"),
    UNREADABLE_IMAGE("recognised.unreadableImage"),
    PHOTO_NOT_SUPPORTED("recognised.photoNotSupported"),
    READING_ABOVE_MAXIMUM("recognised.readingAboveMaximum"),
    READING_ABOVE_MAXIMUM_VALUE("recognised.readingAboveMaximumValue"),
    READING_ABOVE_MAXIMUM_VALUE_UNIT("recognised.readingAboveMaximumValueUnit"),
    UNIT_MINUTES("recognised.unitMinutes"),
    PDU_DAY_TOO_LONG("recognised.pduDayTooLong"),
    PDU_RUN_TOO_LONG("recognised.pduRunTooLong"),
    OCR_FAILED("recognised.ocrFailed"),
    READING_UPDATED("recognised.readingUpdated");

    private final String key;

    ConversationMessageCode(String key) {
        this.key = key;
    }

    /** The key of this message in the bundles. */
    public String key() {
        return key;
    }
}
