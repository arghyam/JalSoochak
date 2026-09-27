package org.arghyam.jalsoochak.message.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantRef")
class TenantRefTest {

    @Test
    @DisplayName("upper-cases and trims the state code so equal tenants compare equal")
    void normalisesCode() {
        assertThat(new TenantRef(1, " mp ")).isEqualTo(new TenantRef(1, "MP"));
        assertThat(new TenantRef(1, "mp").code()).isEqualTo("MP");
    }

    @Test
    @DisplayName("collapses a blank state code to null")
    void blankCodeBecomesNull() {
        assertThat(new TenantRef(1, "   ").code()).isNull();
        assertThat(new TenantRef(1, "").code()).isNull();
    }

    @Test
    @DisplayName("isPresent is false only when both halves are missing")
    void isPresent() {
        assertThat(TenantRef.NONE.isPresent()).isFalse();
        assertThat(new TenantRef(null, "  ").isPresent()).isFalse();
        assertThat(new TenantRef(1, null).isPresent()).isTrue();
        assertThat(new TenantRef(null, "MP").isPresent()).isTrue();
    }

    @Test
    @DisplayName("isComplete is true only when both halves are known")
    void isComplete() {
        assertThat(new TenantRef(1, "MP").isComplete()).isTrue();
        assertThat(new TenantRef(1, null).isComplete()).isFalse();
        assertThat(new TenantRef(null, "MP").isComplete()).isFalse();
        assertThat(TenantRef.NONE.isComplete()).isFalse();
    }

    @Test
    @DisplayName("toString is identity only, safe to log")
    void toStringIsIdentityOnly() {
        assertThat(new TenantRef(1, "MP")).hasToString("tenant(1/MP)");
        assertThat(TenantRef.NONE).hasToString("tenant(none)");
    }
}
