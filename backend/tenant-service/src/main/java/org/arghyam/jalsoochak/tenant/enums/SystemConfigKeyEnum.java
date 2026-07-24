package org.arghyam.jalsoochak.tenant.enums;

import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.IncludedWorkStatusesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.RegularityThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Global configuration keys for system-level platform settings.
 * Each key declares the expected DTO class for that key.
 * These are managed by Super Admins and serve as defaults.
 * Implements ConfigKey sealed interface to restrict config keys to known types.
 */
@Getter
@RequiredArgsConstructor
public enum SystemConfigKeyEnum implements ConfigKey {

    /**
     * System Supported Communication Channels.
     * Defines all channels supported at platform level (BFM, ELM, PDU, IOT, MAN).
     * Super User can add/remove supported channels at system level.
     */
    SYSTEM_SUPPORTED_CHANNELS(ChannelListConfigDTO.class),

    /**
     * Water Quantity Supply Threshold.
     * Default platform-level percentage thresholds for undersupply and oversupply relative to Water Norm.
     * Managed by Super User.
     */
    WATER_QUANTITY_SUPPLY_THRESHOLD(WaterSupplyThresholdConfigDTO.class),

    /**
     * BFM Image Reading Confidence Level Threshold.
     * Minimum confidence level for AI Reader (FlowVision) meter readings.
     * Readings below this threshold are marked with low confidence flag.
     * Managed by Super User.
     */
    BFM_IMAGE_READING_CONFIDENCE_LEVEL_THRESHOLD(SimpleConfigValueDTO.class),

    /**
     * Location Affinity Threshold.
     * Configurable parameter for validating whether submitted meter reading
     * is in the vicinity of the Scheme.
     * Managed by Super User.
     */
    LOCATION_AFFINITY_THRESHOLD(SimpleConfigValueDTO.class),

    /**
     * National default for the scheme work statuses counted in analytics dashboards
     * (analytics tenant-0). Codes: 1=Ongoing, 2=Completed, 3=Not Started, 4=Handed Over.
     * Published to analytics via INCLUDED_WORK_STATUSES_UPDATED with tenantId=0.
     * Managed by Super User.
     */
    INCLUDED_WORK_STATUSES(IncludedWorkStatusesConfigDTO.class),

    /**
     * National default for the percentage of days on which a scheme must supply water to count as
     * regular in analytics dashboards (analytics tenant-0). E.g. 90 means a scheme must supply on at
     * least 90% of the window's days. Applies to national screens uniformly and to any tenant that has
     * not set its own value. Published to analytics via REGULARITY_THRESHOLD_UPDATED with tenantId=0.
     * Managed by Super User.
     */
    REGULARITY_THRESHOLD_PERCENT(RegularityThresholdConfigDTO.class);

    private final Class<? extends ConfigValueDTO> dtoClass;

}
