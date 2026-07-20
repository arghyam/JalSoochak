package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IncludedWorkStatusesConfigDTO#validatedWorkStatuses()} — the enforced
 * validation path for the {@code INCLUDED_WORK_STATUSES} config (bean validation does not run on
 * JsonNode-bound generic/system configs).
 */
class IncludedWorkStatusesConfigDTOTest {

    @Test
    void validValues_areDeduplicatedAndSorted() {
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(List.of(4, 1, 4, 2)).build();

        assertThat(dto.validatedWorkStatuses()).containsExactly(1, 2, 4);
    }

    @Test
    void allFourValidStatuses_areAccepted() {
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(List.of(1, 2, 3, 4)).build();

        assertThat(dto.validatedWorkStatuses()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void nullList_throwsInvalidConfigValueException() {
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(null).build();

        assertThatThrownBy(dto::validatedWorkStatuses)
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("at least one work status");
    }

    @Test
    void emptyList_throwsInvalidConfigValueException() {
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(List.of()).build();

        assertThatThrownBy(dto::validatedWorkStatuses)
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("at least one work status");
    }

    @Test
    void valueOutsideAllowedSet_throwsInvalidConfigValueException() {
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(List.of(4, 5)).build();

        assertThatThrownBy(dto::validatedWorkStatuses)
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("5");
    }

    @Test
    void nullElement_throwsInvalidConfigValueException() {
        List<Integer> withNull = new ArrayList<>(Arrays.asList(4, null));
        IncludedWorkStatusesConfigDTO dto =
                IncludedWorkStatusesConfigDTO.builder().workStatuses(withNull).build();

        assertThatThrownBy(dto::validatedWorkStatuses)
                .isInstanceOf(InvalidConfigValueException.class);
    }
}
