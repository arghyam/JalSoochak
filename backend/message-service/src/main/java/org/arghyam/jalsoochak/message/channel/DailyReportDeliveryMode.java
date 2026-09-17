package org.arghyam.jalsoochak.message.channel;

import java.util.Locale;

/**
 * How the Daily Water Service Situation Report PDF reaches the officer on WhatsApp.
 *
 * <p>{@link #DOCUMENT} is the original path: the PDF is registered with Glific via
 * {@code createMessageMedia} and sent as a document HSM, which requires <em>Meta</em> to download the
 * MinIO URL from its own network. Production MinIO answers Indian source addresses only, so that
 * download fails inside Meta with {@code (#131053) Media upload error — Your server hosting media
 * content did not respond back in time} and the officer receives an attachment that will not open.</p>
 *
 * <p>{@link #LINK} sends a text HSM carrying a dynamic-URL button instead. Meta never fetches the
 * file: the button's prefix is frozen in the approved template and only the bucket-and-filename
 * suffix travels per message, so the PDF is fetched by the officer's own phone — from inside India —
 * when they tap the button.</p>
 */
public enum DailyReportDeliveryMode {

    /** PDF attachment. Meta downloads the MinIO URL itself. */
    DOCUMENT,

    /** Text HSM with a dynamic-URL button. The recipient's phone downloads the MinIO URL. */
    LINK;

    /**
     * Parses the configured value, tolerating case and surrounding whitespace so
     * {@code NOTIFICATIONS_DAILY_REPORT_DELIVERY_MODE=link} works as well as {@code LINK}. A blank
     * value means {@code DOCUMENT} — the mode this service has always used — so an unset property
     * changes nothing.
     *
     * @throws IllegalStateException on an unrecognised value, naming the valid ones. A typo would
     *         otherwise be silently read as the wrong mode and only surface as a whole tenant's
     *         reports arriving in the wrong shape after the next daily cron.
     */
    public static DailyReportDeliveryMode from(String value) {
        if (value == null || value.isBlank()) {
            return DOCUMENT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "notifications.daily-report.delivery-mode must be DOCUMENT or LINK but was '"
                            + value + "'", e);
        }
    }
}
