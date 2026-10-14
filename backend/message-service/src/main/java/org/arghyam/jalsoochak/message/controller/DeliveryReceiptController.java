package org.arghyam.jalsoochak.message.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceiptAdapter;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceiptRequest;
import org.arghyam.jalsoochak.message.channel.provider.ReceiptRejectedException;
import org.arghyam.jalsoochak.message.channel.provider.VerifiedReceipts;
import org.arghyam.jalsoochak.message.ledger.NotificationLedger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where providers push delivery reports: {@code POST /api/v1/message/delivery-receipts/{providerId}}.
 *
 * <p>Provider-neutral: the path names the provider, the {@link DeliveryReceiptAdapter} with that id
 * authenticates and parses the request, and every report it yields is applied to the delivery ledger,
 * within the schemas the adapter's credential may change. Open at the gateway and in
 * {@code SecurityConfig}, because providers cannot present our JWTs — every adapter verifies its
 * provider's signature or shared secret instead, before reading anything.</p>
 *
 * <p>A report that names a provider other than the adapter's is dropped: an adapter speaks for its own
 * provider's rows and no one else's.</p>
 *
 * <p>The raw body is read before anything else touches the request: a signature covers the exact bytes,
 * and a servlet that parsed a form body into parameters would have consumed them. The query string is
 * parsed here for the same reason.</p>
 *
 * <p>Answers 404 for an unknown provider, 401 for an unauthenticated request, 400 for an unreadable one,
 * and 200 otherwise — including when no report matched a row, which a provider must not retry.</p>
 */
@RestController
@RequestMapping("/api/v1/message/delivery-receipts")
@Slf4j
public class DeliveryReceiptController {

    /** Far above any provider's batch; a larger body is refused rather than buffered. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final Map<String, DeliveryReceiptAdapter> adapters;
    private final NotificationLedger ledger;

    public DeliveryReceiptController(List<DeliveryReceiptAdapter> adapters, NotificationLedger ledger) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                a -> a.providerId().toLowerCase(Locale.ROOT), Function.identity()));
        this.ledger = ledger;
    }

    @PostMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String providerId, HttpServletRequest request)
            throws IOException {
        DeliveryReceiptAdapter adapter = adapters.get(providerId.toLowerCase(Locale.ROOT));
        if (adapter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown provider"));
        }
        byte[] body = readBody(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "body too large"));
        }
        VerifiedReceipts verified;
        try {
            verified = adapter.parseAndVerify(new DeliveryReceiptRequest(headers(request), body,
                    parseQuery(request.getQueryString())));
        } catch (ReceiptRejectedException e) {
            log.warn("[Receipts] provider={} rejected: {}", adapter.providerId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthenticated"));
        } catch (IllegalArgumentException e) {
            log.warn("[Receipts] provider={} unreadable: {}", adapter.providerId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "unreadable"));
        }
        List<DeliveryReceipt> receipts = verified.receipts();
        int applied = 0;
        for (DeliveryReceipt receipt : receipts) {
            if (!adapter.providerId().equals(receipt.providerId())) {
                log.warn("[Receipts] provider={} returned a report for provider={}; dropped",
                        adapter.providerId(), receipt.providerId());
                continue;
            }
            applied += ledger.applyReceipt(receipt, verified.scope());
        }
        log.info("[Receipts] provider={} reports={} applied={}", adapter.providerId(), receipts.size(), applied);
        return ResponseEntity.ok(Map.of("received", receipts.size(), "applied", applied));
    }

    /** The body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES}. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq > 0 ? pair.substring(0, eq) : pair;
            String value = eq > 0 ? pair.substring(eq + 1) : "";
            params.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }
}
