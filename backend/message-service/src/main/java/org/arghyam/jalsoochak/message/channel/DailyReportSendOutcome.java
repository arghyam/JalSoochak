package org.arghyam.jalsoochak.message.channel;

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
public record DailyReportSendOutcome(GlificSendResult result, Failure failure) {

    /** Why a send failed, in the two fields that make a log line actionable. */
    public record Failure(GlificSendStage stage, String glificErrorKey, String message) {

        /** Glific's error key for logging, or {@code "-"} when the failure came from our own side. */
        public String errorKeyForLog() {
            return glificErrorKey == null || glificErrorKey.isBlank() ? "-" : glificErrorKey;
        }
    }

    public static DailyReportSendOutcome accepted(GlificSendResult result) {
        return new DailyReportSendOutcome(result, null);
    }

    public static DailyReportSendOutcome failed(GlificSendStage stage, String glificErrorKey, String message) {
        return new DailyReportSendOutcome(null, new Failure(stage, glificErrorKey, message));
    }

    /** True when Glific accepted the send. <strong>Not</strong> a delivery confirmation. */
    public boolean accepted() {
        return failure == null;
    }
}
