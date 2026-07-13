package org.arghyam.jalsoochak.tenant.dto.internal;

/**
 * Sealed interface for API configuration value DTOs.
 * Concrete type is determined by the config key — no Jackson type discriminator needed.
 */
public sealed interface ConfigValueDTO permits
        SimpleConfigValueDTO,
        DateFormatConfigDTO,
        ChannelListConfigDTO,
        ReasonListConfigDTO,
        LocationConfigDTO,
        LanguageListConfigDTO,
        MessageBrokerConfigDTO,
        StateITSystemConfigDTO,
        TimeSettingsConfigDTO,
        NudgeTimingConfigDTO,
        EscalationRulesConfigDTO,
        GlificMessagesConfigDTO,
        WaterSupplyThresholdConfigDTO,
        IncludedWorkStatusesConfigDTO {
}
