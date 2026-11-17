package org.arghyam.jalsoochak.analytics.config;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the phased-rollout guard in front of every analytics route. Absent while the switch is off,
 * so today's behaviour runs no extra query.
 */
@Configuration
@ConditionalOnProperty(prefix = "analytics.phased-rollout", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PhasedRolloutWebMvcConfig implements WebMvcConfigurer {

    private final RegionOnboardingService regionOnboardingService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RegionOnboardingInterceptor(regionOnboardingService))
                .addPathPatterns("/api/v1/analytics/**");
    }
}
