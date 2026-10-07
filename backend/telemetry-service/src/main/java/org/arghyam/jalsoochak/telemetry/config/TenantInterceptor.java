package org.arghyam.jalsoochak.telemetry.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

public class TenantInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);
    public static final String TENANT_HEADER = "X-Tenant-Code";
    private static final Pattern SAFE_TENANT_CODE = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String tenantCode = request.getHeader(TENANT_HEADER);
        if (tenantCode != null) {
            String normalized = tenantCode.trim();
            if (!normalized.isEmpty() && !SAFE_TENANT_CODE.matcher(normalized).matches()) {
                log.warn("Rejected invalid tenant code format in header");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Tenant-Code format");
                return false;
            }
            if (!normalized.isEmpty()) {
                TenantContext.setSchema("tenant_" + normalized.toLowerCase());
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
