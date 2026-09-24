package org.arghyam.jalsoochak.telemetry.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The exact set of chatbot webhook routes protected by {@link WebhookAuthFilter}.
 *
 * <p><b>Why a closed allowlist rather than a prefix rule on {@code /api/v1/telemetry/**}.</b>
 * That prefix is shared with the vendor ingestion endpoints on {@code ReadingIngestController}
 * and {@code MultiFormatReadingController}, which authenticate with a different credential
 * ({@code X-Api-Key}, issued per tenant to state IT departments). {@code /readings} and
 * {@code /schemes} are each shared prefixes across both families — {@code POST /schemes} is a chatbot
 * webhook while {@code PATCH /schemes/{id}/yesterday-final-reading} is an ingestion route. A prefix
 * rule would therefore reject vendor traffic that carries the correct credential for its own family.
 *
 * <p><b>The allowlist's weakness, and how it is closed.</b> A newly added webhook would be silently
 * unprotected. {@code WebhookRouteCoverageTest} asserts this set is exactly equal to the routes
 * declared across every {@link WebhookRoute} controller, so adding a webhook without listing it here
 * fails the build — whichever webhook controller it lands on.
 */
public final class WebhookRoutes {

    /** Class-level {@code @RequestMapping} of every {@link WebhookRoute} controller. */
    public static final String BASE_PATH = "/api/v1/telemetry";

    /**
     * Paths relative to {@link #BASE_PATH}, as declared by the controller's {@code @PostMapping}s.
     * Kept relative so the coverage test can compare against the annotation values directly.
     */
    private static final Set<String> RELATIVE_PATHS = Set.of(
            "/readings/whatsapp",
            // Deprecated alias of /readings/whatsapp; protected for as long as it is served.
            "/readings/glific",
            "/intro",
            "/closing",
            "/language/selection",
            "/selected/language",
            "/channel/selection",
            "/selected/channel",
            "/schemes",
            "/trigger-welcome-message",
            "/scheme/selected",
            "/item/selection",
            "/selected/item",
            "/meter-change",
            "/issue-report",
            "/issue-report/submit",
            "/issue-report/telemetry",
            "/issue-report/telemetry/submit",
            "/meter/issue-report",
            "/meter/meter-change",
            "/meter/meter-change/submit",
            "/others",
            "/others/submitted",
            "/take-meter-reading",
            "/manual-reading",
            "/location",
            "/update-previous-reading"
    );

    /** Every chatbot route is a POST; nothing else on a webhook controller is mapped. */
    private static final String METHOD = "POST";

    private static final Set<String> ABSOLUTE_PATHS = buildAbsolutePaths();

    private WebhookRoutes() {
    }

    private static Set<String> buildAbsolutePaths() {
        Set<String> paths = new LinkedHashSet<>(RELATIVE_PATHS.size());
        for (String relative : RELATIVE_PATHS) {
            paths.add(BASE_PATH + relative);
        }
        return Set.copyOf(paths);
    }

    /** Relative paths, for the coverage guard test. */
    public static Set<String> relativePaths() {
        return RELATIVE_PATHS;
    }

    /** Absolute paths, for diagnostics and tests. */
    public static Set<String> absolutePaths() {
        return ABSOLUTE_PATHS;
    }

    /**
     * @param method HTTP method as reported by the container
     * @param normalizedPath request path with the context path removed and normalized by
     *                       {@link WebhookAuthFilter}
     * @return whether this request targets a chatbot webhook and must therefore carry a webhook token
     */
    public static boolean isProtected(String method, String normalizedPath) {
        if (method == null || normalizedPath == null) {
            return false;
        }
        return METHOD.equals(method.toUpperCase(Locale.ROOT)) && ABSOLUTE_PATHS.contains(normalizedPath);
    }
}
