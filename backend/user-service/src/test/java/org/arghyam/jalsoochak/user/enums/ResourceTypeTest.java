package org.arghyam.jalsoochak.user.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard test: {@link ResourceType#key()} is persisted in
 * {@code data_versions_table.resource_type} and must remain stable across
 * releases. A failure here is a deliberate cross-check that someone is not
 * accidentally renaming an enum constant or its key string.
 */
@DisplayName("ResourceType")
class ResourceTypeTest {

    @Test
    @DisplayName("STAFF_USERS exposes the persisted key \"STAFF_USERS\"")
    void staffUsersKeyStable() {
        assertThat(ResourceType.STAFF_USERS.key()).isEqualTo("STAFF_USERS");
    }

    @Test
    @DisplayName("every constant has a non-blank key matching its name")
    void everyConstantHasMatchingKey() {
        for (ResourceType rt : ResourceType.values()) {
            assertThat(rt.key()).isNotBlank();
            assertThat(rt.key()).isEqualTo(rt.name());
        }
    }
}
