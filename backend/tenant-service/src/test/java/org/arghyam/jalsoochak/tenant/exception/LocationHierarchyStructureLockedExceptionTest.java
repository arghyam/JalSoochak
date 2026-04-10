package org.arghyam.jalsoochak.tenant.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationHierarchyStructureLockedException")
class LocationHierarchyStructureLockedExceptionTest {

    @Test
    @DisplayName("is a RuntimeException")
    void isRuntimeException() {
        assertThat(new LocationHierarchyStructureLockedException("LGD", 5))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("getMessage contains hierarchy type and seeded count")
    void message_containsHierarchyTypeAndSeededCount() {
        LocationHierarchyStructureLockedException ex =
                new LocationHierarchyStructureLockedException("LGD", 42);

        assertThat(ex.getMessage())
                .contains("LGD")
                .contains("42");
    }

    @Test
    @DisplayName("getHierarchyType returns the hierarchy type passed to constructor")
    void getHierarchyType_returnsConstructorValue() {
        LocationHierarchyStructureLockedException ex =
                new LocationHierarchyStructureLockedException("DEPARTMENT", 10);

        assertThat(ex.getHierarchyType()).isEqualTo("DEPARTMENT");
    }

    @Test
    @DisplayName("getSeededCount returns the seeded count passed to constructor")
    void getSeededCount_returnsConstructorValue() {
        LocationHierarchyStructureLockedException ex =
                new LocationHierarchyStructureLockedException("LGD", 100);

        assertThat(ex.getSeededCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("message explains that only level name changes are permitted")
    void message_mentionsAllowedOperation() {
        LocationHierarchyStructureLockedException ex =
                new LocationHierarchyStructureLockedException("LGD", 1);

        assertThat(ex.getMessage()).containsIgnoringCase("level name");
    }

    @Test
    @DisplayName("seededCount of 1 is present in the message")
    void message_withSingleSeededRecord() {
        LocationHierarchyStructureLockedException ex =
                new LocationHierarchyStructureLockedException("LGD", 1);

        assertThat(ex.getMessage()).contains("1");
    }
}
