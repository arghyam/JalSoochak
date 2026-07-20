package org.arghyam.jalsoochak.tenant.enums;

import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.DateFormatConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.GlificMessagesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.MessageBrokerConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ReasonListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.IncludedWorkStatusesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.RegularityThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.StateITSystemConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TimeSettingsConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.NudgeTimingConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EscalationRulesConfigDTO;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Exhaustive list of allowed configuration keys for tenants.
 * Each key declares its storage type and the expected DTO class for that key.
 * Implements ConfigKey sealed interface to restrict config keys to known types.
 */
@Getter
@RequiredArgsConstructor
public enum TenantConfigKeyEnum implements ConfigKey {

    /**
     * Date format for screen display.
     * Format: DD-MON-YYYY with 24H time format and timezone (e.g., Asia/Kolkata).
     */
    DATE_FORMAT_SCREEN(ConfigType.GENERIC, DateFormatConfigDTO.class, true, false, true),

    /**
     * Date format for table display.
     * Format: DD/MM/YYYY with 24H time format and timezone (e.g., Asia/Kolkata).
     */
    DATE_FORMAT_TABLE(ConfigType.GENERIC, DateFormatConfigDTO.class, true, false, true),

    /**
     * Supported communication channels for this tenant.
     * Subset of channels defined at system level (SYSTEM_SUPPORTED_CHANNELS).
     * State Admin can enable/disable channels: BFM, ELM, PDU, IOT, MAN.
     */
    TENANT_SUPPORTED_CHANNELS(ConfigType.GENERIC, ChannelListConfigDTO.class, false, false, true),

    /**
     * Tenant logo file.
     * Managed exclusively via PUT /{tenantId}/logo — not writable through the generic config endpoint.
     * Use GET /{tenantId}/logo to retrieve the logo image.
     */
    TENANT_LOGO(ConfigType.GENERIC, SimpleConfigValueDTO.class, false, true, true),

    /**
     * Meter change reasons list with CRUD capability.
     * Default reasons: Meter Replaced, Meter Not Working, Meter Damaged.
     * State Admin can add, update, and delete reasons.
     */
    METER_CHANGE_REASONS(ConfigType.GENERIC, ReasonListConfigDTO.class, false, false, true),

    /**
     * Supported languages for the tenant (up to 4 languages with preference order).
     * Stored in tenant-specific language_master_table.
     */
    SUPPORTED_LANGUAGES(ConfigType.SPECIALIZED, LanguageListConfigDTO.class, true, false, true),

    /**
     * Location verification requirement flag.
     * Yes or No. Default: No.
     * Determines if GPS location check is required for meter readings.
     */
    LOCATION_CHECK_REQUIRED(ConfigType.GENERIC, SimpleConfigValueDTO.class, false, false, true),

    /**
     * Glific WhatsApp message templates configuration.
     * Defines all screens, prompts, options, messages, and reasons for the conversation flow.
     * Includes multilingual support (all Indian official languages).
     * Provides a hierarchical and maintainable structure for all Glific conversation templates.
     */
    GLIFIC_MESSAGE_TEMPLATES(ConfigType.GENERIC, GlificMessagesConfigDTO.class, false, false, false),

    /**
     * Glific Connection Settings.
     * Contains API credentials and endpoints for WhatsApp integration.
     */
    MESSAGE_BROKER_CONNECTION_SETTINGS(ConfigType.GENERIC, MessageBrokerConfigDTO.class, false, true, false),

    /**
     * State IT Systems API Endpoint and Credentials.
     * For integration with state-level systems and data exchange.
     */
    STATE_IT_SYSTEM_CONNECTION(ConfigType.GENERIC, StateITSystemConfigDTO.class, false, false, false),

    /**
     * Water Norm for this tenant.
     * Example: 55 LPCD (Liters Per Capita Per Day).
     */
    WATER_NORM(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, true),

    /**
     * Tenant-level Water Quantity Supply Threshold.
     * Percentage thresholds for undersupply and oversupply relative to Water Norm,
     * overriding the system-level default for this tenant.
     */
    TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD(ConfigType.GENERIC, WaterSupplyThresholdConfigDTO.class, false, false, true),

    /**
     * Pump Operator Reminder Nudge Configuration.
     * Defines the absolute time by which reminder message is sent if meter reading not submitted.
     * Format: { nudge: { schedule: { hour: 8, minute: 0 } } }
     */
    PUMP_OPERATOR_REMINDER_NUDGE_TIME(ConfigType.GENERIC, NudgeTimingConfigDTO.class, false, false, true),

    /**
     * Field Staff Escalation Rules.
     * Defines escalation schedule and multi-level escalation rules based on thresholds and officer types.
     * Escalation messages are sent to field staff (Section Officer, District Officer,ExecutiveEngineer, etc.).
     */
    FIELD_STAFF_ESCALATION_RULES(ConfigType.GENERIC, EscalationRulesConfigDTO.class, false, false, true),

    /**
     * Data Consolidation Time.
     * Absolute time on which the data consolidation cron job runs.
     */
    DATA_CONSOLIDATION_TIME(ConfigType.GENERIC, TimeSettingsConfigDTO.class, false, false, true),

    /**
     * State Data Reconciliation Time.
     * Absolute time for state-level data reconciliation process.
     */
    STATE_DATA_RECONCILIATION_TIME(ConfigType.GENERIC, TimeSettingsConfigDTO.class, false, false, false),

    /**
     * JSON definition for email templates.
     */
    EMAIL_TEMPLATE_JSON(ConfigType.GENERIC, SimpleConfigValueDTO.class, false, false, false),

    /**
     * Display map for department level 1.
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (2–6) must also be FALSE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_1(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for department level 2.
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (3–6) must also be FALSE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_2(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for department level 3.
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (4–6) must also be FALSE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_3(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for department level 4.
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (5–6) must also be FALSE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_4(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for department level 5.
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, level 6 must also be FALSE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_5(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for department level 6.
     * TRUE or FALSE. Default: TRUE.
     */
    DISPLAY_DEPARTMENT_MAP_LEVEL_6(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Average Members Per Household.
     * Positive numeric value (decimals allowed) used as multiplier for
     * calculating family members count for FHTC/Household.
     */
    AVERAGE_MEMBERS_PER_HOUSEHOLD(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, true),

    /**
     * Supply outage reasons list with CRUD capability.
     * Default reasons: Electricity Supply Disconnected, No Electricity Supply Today,
     * Pump Failure, Pipeline Break, Water Quality Issues, Source Drying,
     * Natural Calamity, Others.
     * State Admin can add, update, and delete reasons (except 'Others').
     */
    SUPPLY_OUTAGE_REASONS(ConfigType.GENERIC, ReasonListConfigDTO.class, false, false, true),

    /**
     * Display map for LGD level 1 (e.g. State).
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (2–6) must also be FALSE.
     */
    DISPLAY_MAP_LGD_LEVEL_1(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for LGD level 2 (e.g. District).
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (3–6) must also be FALSE.
     */
    DISPLAY_MAP_LGD_LEVEL_2(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for LGD level 3 (e.g. Block).
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (4–6) must also be FALSE.
     */
    DISPLAY_MAP_LGD_LEVEL_3(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for LGD level 4 (e.g. Panchayat).
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, all lower levels (5–6) must also be FALSE.
     */
    DISPLAY_MAP_LGD_LEVEL_4(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for LGD level 5 (e.g. Village).
     * TRUE or FALSE. Default: TRUE.
     * If set to FALSE, level 6 must also be FALSE.
     */
    DISPLAY_MAP_LGD_LEVEL_5(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Display map for LGD level 6.
     * TRUE or FALSE. Default: TRUE.
     */
    DISPLAY_MAP_LGD_LEVEL_6(ConfigType.GENERIC, SimpleConfigValueDTO.class, true, false, false),

    /**
     * Scheme work statuses whose schemes are counted in this tenant's analytics dashboards.
     * Codes: 1=Ongoing, 2=Completed, 3=Not Started, 4=Handed Over. Example: [4] restricts dashboards
     * to handed-over schemes. Published to analytics via INCLUDED_WORK_STATUSES_UPDATED.
     */
    INCLUDED_WORK_STATUSES(ConfigType.GENERIC, IncludedWorkStatusesConfigDTO.class, false, false, false),

    /**
     * Percentage of days in a window on which a scheme must supply water to count as regular in this
     * tenant's analytics dashboards. E.g. 90 means a scheme must supply on at least 90% of the window's
     * days (rounded half-up, minimum 1 day). Falls back to the national default, then the analytics env
     * default, when unset. Published to analytics via REGULARITY_THRESHOLD_UPDATED.
     */
    REGULARITY_THRESHOLD_PERCENT(ConfigType.GENERIC, RegularityThresholdConfigDTO.class, false, false, false);

    private final ConfigType type;
    private final Class<? extends ConfigValueDTO> dtoClass;
    /**
     * When true, this key's raw config value is included in the GET /public-config response
     * (no authentication required). Only set this for keys whose stored value is directly
     * useful to public consumers (e.g. a scalar setting). Do NOT set this for keys whose
     * stored value is an internal implementation detail — expose those through a dedicated
     * public endpoint instead.
     */
    private final boolean isPublic;
    /**
     * When true, this key's value is managed by a dedicated service endpoint and
     * cannot be written through the generic PUT /config endpoint.
     */
    private final boolean managedValue;
    /**
     * When true, this key must be configured before the tenant can transition from
     * ONBOARDED to CONFIGURED status. All mandatory keys must be present for the
     * auto-transition to trigger.
     */
    private final boolean mandatory;

    /**
     * Returns all config keys that are required for the ONBOARDED → CONFIGURED transition.
     */
    public static EnumSet<TenantConfigKeyEnum> getMandatoryKeys() {
        return Arrays.stream(values())
                .filter(TenantConfigKeyEnum::isMandatory)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TenantConfigKeyEnum.class)));
    }

    public enum ConfigType {
        GENERIC, // Stored as KV in common_schema.tenant_config_master_table
        SPECIALIZED // Stored in specific tables in tenant schema
    }
}
