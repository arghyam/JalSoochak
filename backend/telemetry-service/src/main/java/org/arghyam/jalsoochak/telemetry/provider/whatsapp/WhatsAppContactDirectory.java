package org.arghyam.jalsoochak.telemetry.provider.whatsapp;

/**
 * The WhatsApp provider's record of an operator's contact — the one place the provider keeps
 * operator details of its own, which its templates and flows read from.
 */
public interface WhatsAppContactDirectory {

    /**
     * Records the operator's chosen language against their contact at the provider, so the
     * provider's own messages follow it. The update runs in the background: a failure there is
     * logged, not raised to the caller. Does nothing when contact sync is disabled, or when the phone
     * number or the language cannot be resolved.
     *
     * @param contactPhone     the operator's phone number, with or without a leading {@code +}
     * @param selectedLanguage a language name, alias or numeric language id
     */
    void syncContactLanguageAsync(String contactPhone, String selectedLanguage);
}
