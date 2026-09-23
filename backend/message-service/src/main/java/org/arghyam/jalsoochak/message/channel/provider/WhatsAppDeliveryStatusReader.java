package org.arghyam.jalsoochak.message.channel.provider;

import org.arghyam.jalsoochak.message.dto.WhatsAppMessageStatus;

import java.time.Instant;
import java.util.List;

/**
 * Port for reading back what the WhatsApp Business Solution Provider (BSP) learned about the messages
 * we sent — delivered, read or rejected.
 *
 * <p>A send the provider accepts is not a message that arrived: the BSP and Meta act after the send
 * call returns and report delivery status to the provider, not to us. This port asks. Callers depend on
 * it only; the provider's query language, pagination and error payload stay inside the adapter.</p>
 *
 * <p>Both methods work on a time window and one {@code bspStatus} at a time, and return the whole
 * organisation's traffic in that window: nudges, OTPs, flow and inbound messages as well as reports.
 * Filtering by template and direction is the caller's job, so a caller can also see what it
 * discarded.</p>
 */
public interface WhatsAppDeliveryStatusReader {

    /**
     * How many messages of a given status the provider holds in the window.
     *
     * <p>A sanity total, not a per-template count: it makes an unexpectedly large window visible before
     * any page is fetched.</p>
     *
     * @param bspStatus  the provider's raw status to count; blank counts every status
     * @param dateColumn the timestamp the window filters on; blank means the adapter's default
     * @return the count, or {@code -1} if the provider did not answer with a number
     */
    int countMessages(Instant from, Instant to, String bspStatus, String dateColumn);

    /**
     * Pages through every message of one status in the window.
     *
     * @param bspStatus  the provider's raw status to fetch; blank fetches every status
     * @param dateColumn the timestamp the window filters on and orders by
     * @param maxPages   hard stop so a pathological window cannot consume the provider's throttle
     *                   budget. Hitting it is logged at {@code WARN} — a truncated pass that looked
     *                   complete would silently under-report delivery
     * @return every message returned, unfiltered
     */
    List<WhatsAppMessageStatus> fetchMessages(Instant from, Instant to, String bspStatus,
                                              String dateColumn, int pageSize, int maxPages);
}
