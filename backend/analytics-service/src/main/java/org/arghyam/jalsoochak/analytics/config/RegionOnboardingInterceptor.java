package org.arghyam.jalsoochak.analytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.springframework.util.NumberUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Phased-rollout guard: rejects a request that names a region that isn't onboarded, before the
 * controller, and so the response cache, runs. {@link PhasedRolloutWebMvcConfig} registers it only
 * when the switch is on. Deliberately not a bean, so the {@code @WebMvcTest} slices don't pick it up.
 *
 * <p>Ids are parsed the way Spring binds an {@code Integer} request param ({@link NumberUtils#parseNumber}:
 * whitespace stripped, hex accepted), so no spelling the controller accepts gets past the guard, and
 * every value of a repeated param is checked. A value that doesn't parse or isn't positive is skipped,
 * since the controller rejects it.
 */
@RequiredArgsConstructor
public class RegionOnboardingInterceptor implements HandlerInterceptor {

    private static final String TENANT_ID_PARAM = "tenant_id";

    private static final List<Map.Entry<String, RegionHierarchy>> REGION_PARAMS = List.of(
            Map.entry("lgd_id", RegionHierarchy.LGD),
            Map.entry("parent_lgd_id", RegionHierarchy.LGD),
            Map.entry("department_id", RegionHierarchy.DEPARTMENT),
            Map.entry("parent_department_id", RegionHierarchy.DEPARTMENT));

    private final RegionOnboardingService regionOnboardingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        OptionalInt tenantId = positiveInt(request.getParameter(TENANT_ID_PARAM));
        if (tenantId.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, RegionHierarchy> param : REGION_PARAMS) {
            String[] values = request.getParameterValues(param.getKey());
            if (values == null) {
                continue;
            }
            for (String value : values) {
                OptionalInt regionId = positiveInt(value);
                if (regionId.isPresent()) {
                    regionOnboardingService.requireOnboarded(
                            tenantId.getAsInt(), param.getValue(), regionId.getAsInt());
                }
            }
        }
        return true;
    }

    private static OptionalInt positiveInt(String value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            int parsed = NumberUtils.parseNumber(value, Integer.class);
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (IllegalArgumentException e) {
            return OptionalInt.empty();
        }
    }
}
