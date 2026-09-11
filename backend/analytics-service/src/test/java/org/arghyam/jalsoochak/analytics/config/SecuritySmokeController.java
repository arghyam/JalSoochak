package org.arghyam.jalsoochak.analytics.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecuritySmokeController {

    /**
     * A path that is on {@code SecurityConfig.PUBLIC_ANALYTICS_ENDPOINTS}. Stands in for the real
     * controller method so the filter chain can be exercised without loading it.
     */
    @GetMapping("/api/v1/analytics/submission-status")
    String publicAnalytics() {
        return "public";
    }

    /** Same path, non-GET: the analytics permitAll is GET-only. */
    @PutMapping("/api/v1/analytics/submission-status")
    String publicAnalyticsPut() {
        return "mutated";
    }

    /**
     * An analytics path that is deliberately <em>not</em> on the allowlist. Under the old
     * {@code /api/v1/analytics/**} wildcard this was anonymously readable.
     */
    @GetMapping("/api/v1/analytics/smoke")
    String unlistedAnalytics() {
        return "ok";
    }

    @GetMapping("/internal/smoke")
    String internalSmoke() {
        return "internal";
    }

    @GetMapping("/swagger-ui/index.html")
    String swaggerUi() {
        return "swagger";
    }
}
