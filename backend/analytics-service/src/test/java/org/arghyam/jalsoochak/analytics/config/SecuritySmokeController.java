package org.arghyam.jalsoochak.analytics.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecuritySmokeController {

    @GetMapping("/api/v1/analytics/smoke")
    String analyticsSmoke() {
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
