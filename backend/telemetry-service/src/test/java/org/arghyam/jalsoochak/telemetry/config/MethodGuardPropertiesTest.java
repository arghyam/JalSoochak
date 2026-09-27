package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MethodGuardProperties")
class MethodGuardPropertiesTest {

    @Test
    @DisplayName("defaults to ENFORCE, so the guard cannot be shipped disabled by omission")
    void defaultsToEnforce() {
        MethodGuardProperties props = new MethodGuardProperties();
        props.init();

        assertThat(props.getResolvedMode()).isEqualTo(MethodGuardProperties.Mode.ENFORCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "off", " off ", "Off"})
    @DisplayName("OFF is accepted in any casing, with surrounding whitespace")
    void offIsParsedCaseInsensitively(String raw) {
        MethodGuardProperties props = properties(raw);

        assertThat(props.getResolvedMode()).isEqualTo(MethodGuardProperties.Mode.OFF);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank mode fails startup rather than silently defaulting")
    void blankModeFailsStartup(String raw) {
        assertThatThrownBy(() -> properties(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.method-guard.mode");
    }

    @Test
    @DisplayName("a null mode fails startup")
    void nullModeFailsStartup() {
        assertThatThrownBy(() -> properties(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.method-guard.mode");
    }

    @Test
    @DisplayName("an unknown mode fails startup and names the accepted values")
    void unknownModeFailsStartup() {
        // AUDIT is the likely mistake, since telemetry.webhook.auth.mode accepts it.
        assertThatThrownBy(() -> properties("AUDIT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDIT")
                .hasMessageContaining("ENFORCE, OFF");
    }

    private static MethodGuardProperties properties(String mode) {
        MethodGuardProperties props = new MethodGuardProperties();
        props.setMode(mode);
        props.init();
        return props;
    }
}
