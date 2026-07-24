package org.arghyam.jalsoochak.tenant.enums;

import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SystemConfigKeyEnum")
class SystemConfigKeyEnumTest {

    @Test
    @DisplayName("SYSTEM_SUPPORTED_CHANNELS has ChannelListConfigDTO class")
    void systemSupportedChannels_hasDtoClass() {
        assertThat(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.getDtoClass())
                .isEqualTo(ChannelListConfigDTO.class);
    }

    @Test
    @DisplayName("WATER_QUANTITY_SUPPLY_THRESHOLD has WaterSupplyThresholdConfigDTO class")
    void waterQuantitySupplyThreshold_hasDtoClass() {
        assertThat(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.getDtoClass())
                .isEqualTo(WaterSupplyThresholdConfigDTO.class);
    }

    @Test
    @DisplayName("BFM_IMAGE_READING_CONFIDENCE_LEVEL_THRESHOLD has SimpleConfigValueDTO class")
    void bfmConfidenceThreshold_hasDtoClass() {
        assertThat(SystemConfigKeyEnum.BFM_IMAGE_READING_CONFIDENCE_LEVEL_THRESHOLD.getDtoClass())
                .isEqualTo(SimpleConfigValueDTO.class);
    }

    @Test
    @DisplayName("LOCATION_AFFINITY_THRESHOLD has SimpleConfigValueDTO class")
    void locationAffinityThreshold_hasDtoClass() {
        assertThat(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD.getDtoClass())
                .isEqualTo(SimpleConfigValueDTO.class);
    }

    @ParameterizedTest(name = "{0} implements ConfigKey sealed interface")
    @EnumSource(SystemConfigKeyEnum.class)
    @DisplayName("every key implements the ConfigKey sealed interface")
    void allKeys_implementConfigKey(SystemConfigKeyEnum key) {
        assertThat(key).isInstanceOf(ConfigKey.class);
    }

    @ParameterizedTest(name = "{0} has a non-null dtoClass")
    @EnumSource(SystemConfigKeyEnum.class)
    @DisplayName("every key has a non-null DTO class")
    void allKeys_haveNonNullDtoClass(SystemConfigKeyEnum key) {
        assertThat(key.getDtoClass()).isNotNull();
    }

    @Test
    @DisplayName("enum has exactly 6 values")
    void enumHasSixValues() {
        assertThat(SystemConfigKeyEnum.values()).hasSize(6);
    }
}
