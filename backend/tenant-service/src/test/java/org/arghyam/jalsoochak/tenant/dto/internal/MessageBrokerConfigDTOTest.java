package org.arghyam.jalsoochak.tenant.dto.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBrokerConfigDTOTest {

    @Test
    void build_setsAllFields() {
        MessageBrokerConfigDTO dto = MessageBrokerConfigDTO.builder()
                .apiUrl("https://api.glific.com")
                .apiKey("key123")
                .organizationId("org456")
                .build();

        assertThat(dto.getApiUrl()).isEqualTo("https://api.glific.com");
        assertThat(dto.getOrganizationId()).isEqualTo("org456");
    }

    @Test
    void getAdditionalSettings_returnsEmptyMap_whenNoneAdded() {
        MessageBrokerConfigDTO dto = MessageBrokerConfigDTO.builder()
                .apiUrl("https://api.glific.com")
                .apiKey("key123")
                .organizationId("org456")
                .build();

        assertThat(dto.getAdditionalSettings()).isNotNull().isEmpty();
    }

    @Test
    void addAdditionalSetting_storesAndRetrieves() {
        MessageBrokerConfigDTO dto = new MessageBrokerConfigDTO();
        dto.addAdditionalSetting("retryCount", 3);

        assertThat(dto.getAdditionalSettings()).containsEntry("retryCount", 3);
    }

    @Test
    void toString_doesNotExposeApiKey() {
        MessageBrokerConfigDTO dto = MessageBrokerConfigDTO.builder()
                .apiUrl("https://api.glific.com")
                .apiKey("supersecretkey")
                .organizationId("org456")
                .build();

        assertThat(dto.toString()).doesNotContain("supersecretkey");
    }

    @Test
    void equalsAndHashCode_excludeApiKey() {
        MessageBrokerConfigDTO dto1 = MessageBrokerConfigDTO.builder()
                .apiUrl("https://api.glific.com")
                .apiKey("key1")
                .organizationId("org456")
                .build();

        MessageBrokerConfigDTO dto2 = MessageBrokerConfigDTO.builder()
                .apiUrl("https://api.glific.com")
                .apiKey("key2")
                .organizationId("org456")
                .build();

        assertThat(dto1).isEqualTo(dto2);
        assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
    }

    @Test
    void noArgsConstructor_createsInstanceWithEmptySettings() {
        MessageBrokerConfigDTO dto = new MessageBrokerConfigDTO();
        assertThat(dto.getAdditionalSettings()).isNotNull().isEmpty();
    }
}
