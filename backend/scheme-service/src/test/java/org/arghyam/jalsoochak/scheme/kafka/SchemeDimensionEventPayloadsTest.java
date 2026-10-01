package org.arghyam.jalsoochak.scheme.kafka;

import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository.SchemeAnalyticsRow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeDimensionEventPayloadsTest {

    @Test
    void buildsTheSchemeUpdatedEventAnalyticsConsumes() {
        Map<String, Object> payload = SchemeDimensionEventPayloads.schemeUpdated(1,
                new SchemeAnalyticsRow(5, "34123", "8165607", "CHAPATOLI", 27.1, 95.1, 4, 0, 77, null));

        assertThat(payload).containsEntry("eventType", "SCHEME_UPDATED").containsEntry("schemeId", 5)
                .containsEntry("stateSchemeId", 34123).containsEntry("centreSchemeId", 8165607)
                .containsEntry("parentLgdLocationId", 77).containsEntry("level5LgdId", 77)
                .containsEntry("level1DeptId", 0).containsEntry("work_status", 4).containsEntry("operating_status", 0);
    }

    @Test
    void anUnparseableIdBecomesZero() {
        assertThat(SchemeDimensionEventPayloads.safeParseInt("SS-1")).isZero();
        assertThat(SchemeDimensionEventPayloads.safeParseInt(null)).isZero();
    }
}
