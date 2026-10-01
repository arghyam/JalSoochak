package org.arghyam.jalsoochak.analytics.helper;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.exception.SingleTenantModeAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rejects the national (cross-tenant) endpoints when {@code analytics.single-tenant-mode} is on.
 */
@Component
@Slf4j
public class SingleTenantModeGuard {

    private final boolean singleTenantMode;

    public SingleTenantModeGuard(@Value("${analytics.single-tenant-mode:false}") boolean singleTenantMode) {
        this.singleTenantMode = singleTenantMode;
    }

    public void rejectIfSingleTenantMode(String apiName) {
        if (!singleTenantMode) {
            return;
        }
        String message = "API '" + apiName + "' cannot be accessed when single-tenant mode is enabled";
        log.warn(message);
        throw new SingleTenantModeAccessException(message);
    }
}
