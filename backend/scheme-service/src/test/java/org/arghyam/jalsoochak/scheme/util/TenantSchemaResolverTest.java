package org.arghyam.jalsoochak.scheme.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSchemaResolverTest {

    @Test
    void requireSchemaNameFromTenantCode_returnsNormalizedSchema() {
        assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode(" Ka_12 "))
                .isEqualTo("tenant_ka_12");
    }

    @Test
    void requireSchemaNameFromTenantCode_rejectsBlank() {
        assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode(" "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void requireSchemaNameFromTenantCode_rejectsUnsafeValue() {
        assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode("bad-code!"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
