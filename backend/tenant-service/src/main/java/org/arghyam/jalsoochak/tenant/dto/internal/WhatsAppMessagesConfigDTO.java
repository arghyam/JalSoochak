package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO for the WhatsApp chatbot conversation flow configuration.
 * 
 * Defines all screens, prompts, options, messages, and reasons for the conversation flow.
 * Includes multilingual support (all Indian official languages).
 * 
 * This is a single configuration key (WHATSAPP_MESSAGE_TEMPLATES) that replaces multiple
 * individual message keys, providing a hierarchical and maintainable structure.
 * 
 * Example structure:
 * {
 *   "version": 1,
 *   "screens": {
 *     "LANGUAGE_SELECTION": {
 *       "prompt": { "en": "...", "hi": "..." },
 *       "options": {
 *         "OPTION_1": { "order": 1, "label": { "en": "English", "hi": "अंग्रेज़ी" } },
 *         "OPTION_2": { "order": 2, "label": { "en": "Hindi", "hi": "हिंदी" } }
 *       },
 *       "confirmationTemplate": { "en": "...", "hi": "..." }
 *     },
 *     "INTRO_MESSAGE": {
 *       "message": { "en": "Hello {name}...", "hi": "नमस्ते {name}..." }
 *     }
 *   }
 * }
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class WhatsAppMessagesConfigDTO implements ConfigValueDTO {
    /**
     * Schema version for future compatibility and migrations.
     * Increment when the structure changes in a breaking way.
     */
    @NotNull(message = "Version is required")
    @Positive(message = "Version must be positive")
    private Integer version;
    
    /**
     * Map of screen configurations.
     * Key: Screen identifier (e.g., LANGUAGE_SELECTION, CHANNEL_SELECTION, INTRO_MESSAGE, ISSUE_REPORT, etc.)
     * Value: Screen configuration with prompts, options, messages, etc.
     */
    @NotEmpty(message = "At least one screen must be defined")
    private Map<String, @NotNull @Valid ScreenConfigDTO> screens;
}
