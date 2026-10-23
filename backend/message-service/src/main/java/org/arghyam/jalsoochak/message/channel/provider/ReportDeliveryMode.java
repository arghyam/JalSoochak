package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Locale;

/**
 * How a situation report PDF — daily or weekly — reaches the officer on WhatsApp. Each report has its
 * own property ({@code notifications.daily-report.delivery-mode},
 * {@code notifications.weekly-report.delivery-mode}) and its own approved templates per mode.
 *
 * <p>{@link #DOCUMENT} is the original path: the PDF is registered with the WhatsApp provider via
 * {@code createMessageMedia} and sent as a document HSM, which requires <em>Meta</em> to download the
 * report URL from its own network. The production object store answers Indian source addresses only,
 * so that download fails inside Meta with {@code (#131053) Media upload error — Your server hosting
 * media content did not respond back in time} and the officer receives an attachment that will not
 * open.</p>
 *
 * <p>{@link #LINK} sends a text HSM carrying a dynamic-URL button instead. Meta never fetches the
 * file: the button's prefix is frozen in the approved template and only the bucket-and-filename
 * suffix travels per message, so the PDF is fetched by the officer's own phone — from inside India —
 * when they tap the button.</p>
 */
public enum ReportDeliveryMode {

    /** PDF attachment. Meta downloads the report URL itself. */
    DOCUMENT,

    /** Text HSM with a dynamic-URL button. The recipient's phone downloads the report URL. */
    LINK;

    /**
     * Parses a configured value, tolerating case and surrounding whitespace so
     * {@code NOTIFICATIONS_DAILY_REPORT_DELIVERY_MODE=link} works as well as {@code LINK}.
     *
     * @param value     the configured value
     * @param whenBlank the mode a blank or absent value stands for: the mode that report sent before its
     *                  property existed, so an unset property changes nothing
     * @param property  the property the value came from, named in the error
     * @throws IllegalStateException on an unrecognised value, naming the property and the valid values. A
     *         typo would otherwise be silently read as the wrong mode and only surface as a whole tenant's
     *         reports arriving in the wrong shape after the next scheduled run.
     */
    public static ReportDeliveryMode from(String value, ReportDeliveryMode whenBlank, String property) {
        if (value == null || value.isBlank()) {
            return whenBlank;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    property + " must be DOCUMENT or LINK but was '" + value + "'", e);
        }
    }
}
