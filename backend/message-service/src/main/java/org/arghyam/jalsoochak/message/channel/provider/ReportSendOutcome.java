package org.arghyam.jalsoochak.message.channel.provider;

/**
 * The result of one daily-report send attempt — either Glific accepted it, or it failed at a
 * identifiable stage.
 *
 * <p>Replaces the previous bare {@code boolean}, which told the router that something went wrong but
 * not <em>where</em>. Every failure between registering the media and sending the message collapsed
 * into a single {@code result=FAILED_DELIVERY} line, so the 20 Aug media-fetch incident was
 * indistinguishable in the logs from a template or receiver problem.</p>
 *
 * <p>Exactly one of {@code result} / {@code failure} is non-null.</p>
 */
public record ReportSendOutcome(WhatsAppSendResult result, Failure failure) {

    /** Why a send failed, in the two fields that make a log line actionable. */
    public record Failure(WhatsAppSendStage stage, String errorKey, String message) {

        /** Glific's error key for logging, or {@code "-"} when the failure came from our own side. */
        public String errorKeyForLog() {
            return errorKey == null || errorKey.isBlank() ? "-" : errorKey;
        }
    }

    public static ReportSendOutcome accepted(WhatsAppSendResult result) {
        return new ReportSendOutcome(result, null);
    }

    public static ReportSendOutcome failed(WhatsAppSendStage stage, String errorKey, String message) {
        return new ReportSendOutcome(null, new Failure(stage, errorKey, message));
    }

    /** True when Glific accepted the send. <strong>Not</strong> a delivery confirmation. */
    public boolean accepted() {
        return failure == null;
    }
}
