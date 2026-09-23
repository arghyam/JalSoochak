package org.arghyam.jalsoochak.tenant.enums;

import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.DateFormatConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WhatsAppMessagesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.MessageBrokerConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ReasonListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.IncludedWorkStatusesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.RegularityThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.StateITSystemConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TimeSettingsConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.NudgeTimingConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EscalationRulesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.DailyReportTimingConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WeeklyReportTimingConfigDTO;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Collectors;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * Exhaustive list of allowed configuration keys for tenants.
 * Each key declares its storage type and the expected DTO class for that key.
 * Implements ConfigKey sealed interface to restrict config keys to known types.
 * <p>
 * A renamed key keeps its old name as a legacy alias for a while (see {@link #isLegacyAlias()}), so
 * clients still sending the old name keep working while they switch.
 */
@Getter
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
     * WhatsApp chatbot message templates configuration.
     * Defines all screens, prompts, options, messages, and reasons for the conversation flow.
     * Includes multilingual support (all Indian official languages).
     * Provides a hierarchical and maintainable structure for all chatbot conversation templates.
     */
    WHATSAPP_MESSAGE_TEMPLATES(ConfigType.GENERIC, WhatsAppMessagesConfigDTO.class, false, false, false),

    /**
     * Deprecated name of {@link #WHATSAPP_MESSAGE_TEMPLATES}. It is still accepted in requests, but
     * resolved to the canonical key before anything is read or stored, so the table only ever holds
     * the canonical name. Responses that carry the canonical key also carry this name, with the same
     * value.
     */
    @Deprecated(forRemoval = true)
    GLIFIC_MESSAGE_TEMPLATES(WHATSAPP_MESSAGE_TEMPLATES),

    /**
     * WhatsApp provider connection settings.
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
     * Daily Water Service Situation Report schedule (Section Officers).
     * The report covers the same day from 00:00 up to this time.
     * Format: { dailyReport: { schedule: { hour: 16, minute: 0 } } }
     * Optional: an unset tenant runs on the application default rather than being incomplete.
     */
    DAILY_SITUATION_REPORT_TIME(ConfigType.GENERIC, DailyReportTimingConfigDTO.class, false, false, false),

    /**
     * Weekly Water Service Situation Report schedule and window (Section and Sub-Divisional Officers).
     * Format: { weeklyReport: { schedule: { dayOfWeek: 1, hour: 9, minute: 0 }, weekStartDay: 1 } }
     * <p>
     * {@code schedule} is when the job fires; {@code weekStartDay} is which seven days it reports on.
     * Both use the cron convention 0–7, where both 0 and 7 are Sunday and 1 is Monday. The report always
     * covers the last COMPLETE week beginning on {@code weekStartDay}, so 1 gives Monday–Sunday and 4
     * gives Thursday–Wednesday; it never includes a day that has not finished. The two settings are
     * independent — the scheduler warns when they differ, since the gap is how stale the data is on
     * delivery.
     * <p>
     * Changing {@code weekStartDay} makes the next window overlap the one already reported, so officers
     * may receive two reports covering some of the same days. There is no de-duplication.
     * <p>
     * Optional: an unset tenant runs on the application default (Monday) rather than being incomplete.
     */
    WEEKLY_SITUATION_REPORT_TIME(ConfigType.GENERIC, WeeklyReportTimingConfigDTO.class, false, false, false),

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
    REGULARITY_THRESHOLD_PERCENT(ConfigType.GENERIC, RegularityThresholdConfigDTO.class, false, false, false),

    /**
     * The email account this tenant's own mail is sent through (SendGrid or SMTP), including the
     * from address and the account's template ids. Credentials are NOT here — they live encrypted
     * in {@code common_schema.tenant_provider_secret} and their location is derived by the server.
     * <p>
     * {@code managedValue = true}: written only through
     * {@code PUT /api/v1/tenants/{tenantId}/messaging-providers}, which validates the provider's
     * required fields and checks an SMTP host against MESSAGING_PROVIDER_ALLOWED_HOSTS. The generic
     * config API cannot reach it, so a settings value can never skip those checks.
     * <p>
     * {@code mandatory = false}: a tenant with no settings uses the system default provider, which
     * is today's behaviour, so this must not block the ONBOARDED → CONFIGURED transition.
     */
    EMAIL_PROVIDER_SETTINGS(ConfigType.GENERIC, EmailProviderConfigDTO.class, false, true, false),

    /**
     * The SMS account this tenant's own messages are sent through, including the sender id, the DLT
     * registrations and the OTP text. Credentials are NOT here — see EMAIL_PROVIDER_SETTINGS.
     * <p>
     * A WHATSAPP_PROVIDER_SETTINGS key is deliberately absent: every tenant shares one WhatsApp provider
     * organisation today, so there is nothing per-tenant to store.
     */
    SMS_PROVIDER_SETTINGS(ConfigType.GENERIC, SmsProviderConfigDTO.class, false, true, false);

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
     * The key a legacy alias stands in for; null for every canonical key. See {@link #canonical()}.
     */
    @Getter(AccessLevel.NONE)
    private final TenantConfigKeyEnum aliasOf;

    TenantConfigKeyEnum(ConfigType type, Class<? extends ConfigValueDTO> dtoClass, boolean isPublic,
            boolean managedValue, boolean mandatory) {
        this(type, dtoClass, isPublic, managedValue, mandatory, null);
    }

    /**
     * A legacy alias has the same storage type, DTO and writability as its canonical key. It is never
     * public or mandatory itself, because the canonical key already answers both.
     */
    TenantConfigKeyEnum(TenantConfigKeyEnum canonical) {
        this(canonical.type, canonical.dtoClass, false, canonical.managedValue, false, canonical);
    }

    TenantConfigKeyEnum(ConfigType type, Class<? extends ConfigValueDTO> dtoClass, boolean isPublic,
            boolean managedValue, boolean mandatory, TenantConfigKeyEnum aliasOf) {
        this.type = type;
        this.dtoClass = dtoClass;
        this.isPublic = isPublic;
        this.managedValue = managedValue;
        this.mandatory = mandatory;
        this.aliasOf = aliasOf;
    }

    /**
     * True for the old name of a renamed key, which is kept only so that clients still using it keep
     * working.
     */
    public boolean isLegacyAlias() {
        return aliasOf != null;
    }

    /**
     * The key this one is stored and returned under: the key itself, or, for a legacy alias, the key
     * it stands in for.
     */
    public TenantConfigKeyEnum canonical() {
        return aliasOf == null ? this : aliasOf;
    }

    /**
     * Every key except the legacy aliases. Code that lists or counts all keys iterates this instead of
     * {@link #values()}, so an alias is never reported as a key in its own right.
     */
    public static EnumSet<TenantConfigKeyEnum> canonicalValues() {
        return Arrays.stream(values())
                .filter(key -> !key.isLegacyAlias())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TenantConfigKeyEnum.class)));
    }

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
