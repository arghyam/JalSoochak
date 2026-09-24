package org.arghyam.jalsoochak.analytics.helper;

import org.arghyam.jalsoochak.analytics.exception.SingleTenantModeAccessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleTenantModeGuardTest {

    @Test
    void multiTenantMode_allowsTheCall() {
        SingleTenantModeGuard guard = new SingleTenantModeGuard(false);

        assertThatCode(() -> guard.rejectIfSingleTenantMode("national/dashboard"))
                .doesNotThrowAnyException();
    }

    @Test
    void singleTenantMode_rejectsTheCallNamingTheApi() {
        SingleTenantModeGuard guard = new SingleTenantModeGuard(true);

        assertThatThrownBy(() -> guard.rejectIfSingleTenantMode("national/dashboard"))
                .isInstanceOf(SingleTenantModeAccessException.class)
                .hasMessage("API 'national/dashboard' cannot be accessed when single-tenant mode is enabled");
    }
}
