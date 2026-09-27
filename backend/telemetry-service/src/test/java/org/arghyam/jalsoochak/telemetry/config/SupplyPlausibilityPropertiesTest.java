package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SupplyPlausibilityProperties")
class SupplyPlausibilityPropertiesTest {

    private static SupplyPlausibilityProperties properties(String mode, String limit, String members) {
        SupplyPlausibilityProperties props = new SupplyPlausibilityProperties();
        props.setMode(mode);
        props.setLimitPerPersonLitres(limit);
        props.setDefaultMembersPerHousehold(members);
        return props;
    }

    @Nested
    @DisplayName("mode")
    class ModeParsing {

        @Test
        @DisplayName("defaults to AUDIT so a deploy cannot start quarantining by omission")
        void defaultsToAudit() {
            SupplyPlausibilityProperties props = new SupplyPlausibilityProperties();
            props.init();

            assertThat(props.getResolvedMode()).isEqualTo(SupplyPlausibilityProperties.Mode.AUDIT);
            assertThat(props.isEnforcing()).isFalse();
            assertThat(props.isDisabled()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"enforce", "ENFORCE", "  Enforce  "})
        @DisplayName("is case-insensitive and trimmed")
        void caseInsensitive(String raw) {
            SupplyPlausibilityProperties props = properties(raw, "150", "5");
            props.init();

            assertThat(props.getResolvedMode()).isEqualTo(SupplyPlausibilityProperties.Mode.ENFORCE);
            assertThat(props.isEnforcing()).isTrue();
        }

        @Test
        @DisplayName("OFF reports the check as disabled")
        void offDisablesTheCheck() {
            SupplyPlausibilityProperties props = properties("OFF", "150", "5");
            props.init();

            assertThat(props.isDisabled()).isTrue();
            assertThat(props.isEnforcing()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "ON", "audit-only"})
        @DisplayName("fails startup rather than guessing")
        void rejectsUnknownMode(String raw) {
            SupplyPlausibilityProperties props = properties(raw, "150", "5");

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("telemetry.supply-plausibility.mode");
        }
    }

    @Nested
    @DisplayName("limit-per-person-litres")
    class LimitParsing {

        @Test
        @DisplayName("defaults to 150 L/person/day")
        void defaultsTo150() {
            SupplyPlausibilityProperties props = new SupplyPlausibilityProperties();
            props.init();

            assertThat(props.getResolvedLimitPerPersonLitres()).isEqualByComparingTo("150");
        }

        @Test
        @DisplayName("accepts a decimal override")
        void acceptsDecimal() {
            SupplyPlausibilityProperties props = properties("AUDIT", "82.5", "5");
            props.init();

            assertThat(props.getResolvedLimitPerPersonLitres()).isEqualByComparingTo("82.5");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses to start on a blank limit, which would silently skip every reading")
        void rejectsBlank(String raw) {
            SupplyPlausibilityProperties props = properties("AUDIT", raw, "5");

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("limit-per-person-litres must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("refuses a non-positive limit, which would reject every reading")
        void rejectsNonPositive(String raw) {
            SupplyPlausibilityProperties props = properties("AUDIT", raw, "5");

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be greater than zero");
        }

        @Test
        @DisplayName("refuses a non-numeric limit with a message naming the property")
        void rejectsNonNumeric() {
            SupplyPlausibilityProperties props = properties("AUDIT", "one-fifty", "5");

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("telemetry.supply-plausibility.limit-per-person-litres")
                    .hasMessageContaining("is not a number");
        }
    }

    @Nested
    @DisplayName("default-members-per-household")
    class MembersParsing {

        @Test
        @DisplayName("defaults to 5 persons")
        void defaultsToFive() {
            SupplyPlausibilityProperties props = new SupplyPlausibilityProperties();
            props.init();

            assertThat(props.getResolvedDefaultMembersPerHousehold()).isEqualByComparingTo("5");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("blank means no fallback, so tenants without the config are skipped")
        void blankMeansNoFallback(String raw) {
            SupplyPlausibilityProperties props = properties("AUDIT", "150", raw);
            props.init();

            assertThat(props.getResolvedDefaultMembersPerHousehold()).isNull();
        }

        @Test
        @DisplayName("null is treated the same as blank")
        void nullMeansNoFallback() {
            SupplyPlausibilityProperties props = properties("AUDIT", "150", null);
            props.init();

            assertThat(props.getResolvedDefaultMembersPerHousehold()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-4.5", "four"})
        @DisplayName("a wrong value fails startup rather than becoming a silent no-fallback")
        void rejectsBadValues(String raw) {
            SupplyPlausibilityProperties props = properties("AUDIT", "150", raw);

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("telemetry.supply-plausibility.default-members-per-household");
        }
    }
}
