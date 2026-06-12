package org.arghyam.jalsoochak.tenant.dto.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateITSystemConfigDTOTest {

    @Test
    void build_setsAllFields() {
        StateITSystemConfigDTO dto = StateITSystemConfigDTO.builder()
                .apiEndpoint("https://api.example.com")
                .username("user1")
                .password("secret")
                .organizationCode("ORG001")
                .build();

        assertThat(dto.getApiEndpoint()).isEqualTo("https://api.example.com");
        assertThat(dto.getUsername()).isEqualTo("user1");
        assertThat(dto.getOrganizationCode()).isEqualTo("ORG001");
    }

    @Test
    void getAdditionalSettings_returnsEmptyMap_whenNoneAdded() {
        StateITSystemConfigDTO dto = StateITSystemConfigDTO.builder()
                .apiEndpoint("https://api.example.com")
                .username("user1")
                .password("secret")
                .organizationCode("ORG001")
                .build();

        assertThat(dto.getAdditionalSettings()).isEmpty();
    }

    @Test
    void addAdditionalSetting_storesAndRetrieves_customKey() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();
        dto.addAdditionalSetting("customTimeout", 5000);

        Map<String, Object> settings = dto.getAdditionalSettings();
        assertThat(settings).containsEntry("customTimeout", 5000);
    }

    @Test
    void getAdditionalSettings_filtersOut_knownProperties() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();
        dto.addAdditionalSetting("extraField", "value");

        Map<String, Object> settings = dto.getAdditionalSettings();
        assertThat(settings).containsKey("extraField");
        assertThat(settings).doesNotContainKey("apiEndpoint");
        assertThat(settings).doesNotContainKey("username");
        assertThat(settings).doesNotContainKey("password");
        assertThat(settings).doesNotContainKey("organizationCode");
    }

    @Test
    void addAdditionalSetting_throwsIllegalArgumentException_whenKeyIsNull() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();

        assertThatThrownBy(() -> dto.addAdditionalSetting(null, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void addAdditionalSetting_throwsIllegalArgumentException_whenKeyIsBlank() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();

        assertThatThrownBy(() -> dto.addAdditionalSetting("  ", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void addAdditionalSetting_throwsIllegalArgumentException_whenKeyIsKnownProperty() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();

        assertThatThrownBy(() -> dto.addAdditionalSetting("apiEndpoint", "https://other.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared field");

        assertThatThrownBy(() -> dto.addAdditionalSetting("username", "other"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> dto.addAdditionalSetting("password", "other"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> dto.addAdditionalSetting("organizationCode", "other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addMultipleAdditionalSettings_allPresent() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();
        dto.addAdditionalSetting("key1", "val1");
        dto.addAdditionalSetting("key2", 42);

        Map<String, Object> settings = dto.getAdditionalSettings();
        assertThat(settings).hasSize(2)
                .containsEntry("key1", "val1")
                .containsEntry("key2", 42);
    }

    @Test
    void getAdditionalSettings_returnsUnmodifiableMap() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();
        dto.addAdditionalSetting("k", "v");

        Map<String, Object> settings = dto.getAdditionalSettings();
        assertThatThrownBy(() -> settings.put("newKey", "newVal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noArgsConstructor_createsInstanceWithEmptyAdditionalSettings() {
        StateITSystemConfigDTO dto = new StateITSystemConfigDTO();
        assertThat(dto.getAdditionalSettings()).isNotNull().isEmpty();
    }

    @Test
    void toString_doesNotExposePassword() {
        StateITSystemConfigDTO dto = StateITSystemConfigDTO.builder()
                .apiEndpoint("https://api.example.com")
                .username("user1")
                .password("supersecret")
                .organizationCode("ORG001")
                .build();

        assertThat(dto.toString()).doesNotContain("supersecret");
    }
}
