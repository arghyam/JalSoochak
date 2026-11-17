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
 * <p>Params are read the way Spring binds an {@code Integer} {@code @RequestParam}: by name, or by
 * {@code name[]} when the name is absent, and parsed with {@link NumberUtils#parseNumber} (whitespace
 * stripped, hex accepted). So no spelling the controller accepts gets past the guard. The tenant is the
 * first value, as Spring binds it; every value of a repeated region param is checked. A value that
 * doesn't parse or isn't positive is skipped, since the controller rejects it.
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
        String[] tenantValues = parameterValues(request, TENANT_ID_PARAM);
        OptionalInt tenantId = tenantValues == null || tenantValues.length == 0
                ? OptionalInt.empty()
                : positiveInt(tenantValues[0]);
        if (tenantId.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, RegionHierarchy> param : REGION_PARAMS) {
            String[] values = parameterValues(request, param.getKey());
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

    /** As {@code RequestParamMethodArgumentResolver} resolves a name: its values, else those of {@code name[]}. */
    private static String[] parameterValues(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null ? values : request.getParameterValues(name + "[]");
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
