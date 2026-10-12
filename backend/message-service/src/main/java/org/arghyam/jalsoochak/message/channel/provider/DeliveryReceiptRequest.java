package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Locale;
import java.util.Map;

/**
 * One pushed delivery-report request, as an adapter needs it: the raw body exactly as received — a
 * signature is computed over the bytes, so they must not be re-serialised — and the headers and query
 * parameters.
 *
 * @param headers header names lower-cased
 */
public record DeliveryReceiptRequest(Map<String, String> headers, byte[] body, Map<String, String> query) {

    public DeliveryReceiptRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
        query = query == null ? Map.of() : Map.copyOf(query);
    }

    /** A header by name, case-insensitively, or {@code null}. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
