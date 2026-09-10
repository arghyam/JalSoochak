package org.arghyam.jalsoochak.analytics.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EscalationType")
class EscalationTypeTest {

    @Nested
    @DisplayName("codes")
    class Codes {

        @Test
        @DisplayName("the implausible-supply anomaly is code 10")
        void implausibleSupplyIsTen() {
            // Mirrors AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY in telemetry-service, which is
            // not on this module's classpath; both sides are pinned to the literal instead.
            assertThat(EscalationType.IMPLAUSIBLE_WATER_SUPPLY.code).isEqualTo(10);
            assertThat(EscalationType.fromCode(10)).isEqualTo(EscalationType.IMPLAUSIBLE_WATER_SUPPLY);
        }

        @Test
        @DisplayName("codes are unique and contiguous from 1")
        void codesAreUniqueAndContiguous() {
            assertThat(Arrays.stream(EscalationType.values()).map(t -> t.code).toList())
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(
                            Stream.iterate(1, i -> i + 1).limit(EscalationType.values().length).toList());
        }

        @ParameterizedTest
        @EnumSource(EscalationType.class)
        @DisplayName("every label mirrors its constant name, since the label is what is persisted")
        void labelMirrorsName(EscalationType type) {
            // FactServiceImpl.intCodeToVarchar writes the label into anomaly_table.type, so the label
            // is the stored value the daily-report PDF maps back to a friendly string.
            assertThat(type.label).isEqualTo(type.name());
        }

        @Test
        @DisplayName("an unknown code resolves to null rather than a wrong type")
        void unknownCodeIsNull() {
            assertThat(EscalationType.fromCode(999)).isNull();
            assertThat(EscalationType.fromCode(null)).isNull();
        }
    }

    @Nested
    @DisplayName("scoping sets")
    class ScopingSets {

        @Test
        @DisplayName("the implausible-supply anomaly is scoped to the operator who submitted it")
        void implausibleSupplyIsAUserAnomaly() {
            assertThat(EscalationType.USER_ANOMALIES).contains(EscalationType.IMPLAUSIBLE_WATER_SUPPLY);
        }

        @Test
        @DisplayName("and raises no escalation, despite its name")
        void implausibleSupplyRaisesNoEscalation() {
            // FactServiceImpl writes fact_escalation_table only for WATER_ANOMALIES. Type 10 is
            // deliberately outside that set: the rejection is already answered to the caller and
            // recorded on anomaly_table, and no one is meant to be escalated to. Moving it into
            // WATER_ANOMALIES would start creating escalation rows silently.
            assertThat(EscalationType.WATER_ANOMALIES)
                    .doesNotContain(EscalationType.IMPLAUSIBLE_WATER_SUPPLY);
        }

        @Test
        @DisplayName("the two sets are disjoint, so correlationId keying is unambiguous")
        void setsAreDisjoint() {
            assertThat(EscalationType.WATER_ANOMALIES)
                    .doesNotContainAnyElementsOf(EscalationType.USER_ANOMALIES);
        }

        @Test
        @DisplayName("every type is scoped by exactly one of the two sets")
        void everyTypeIsScoped() {
            // An unscoped type falls through to event.getUuid() for its correlationId, which means no
            // dedup at all - a silent behaviour change for whoever adds the next constant.
            assertThat(EscalationType.values())
                    .allSatisfy(type -> assertThat(
                            EscalationType.WATER_ANOMALIES.contains(type)
                                    || EscalationType.USER_ANOMALIES.contains(type))
                            .as("%s belongs to neither WATER_ANOMALIES nor USER_ANOMALIES", type)
                            .isTrue());
        }
    }
}
