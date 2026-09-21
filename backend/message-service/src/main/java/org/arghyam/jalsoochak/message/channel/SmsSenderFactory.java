package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;

/**
 * PER-TENANT-PROVIDERS: builds an {@link SmsSender} for one tenant's own account (O2-2).
 *
 * <p>{@link SmsCountrySenderFactory} implements it. One factory per {@link SmsProviderType}
 * constant, registered unconditionally.
 *
 * @see EmailSenderFactory for the same port on the email channel
 */
public interface SmsSenderFactory {

    /** The provider this factory builds. Exactly one factory per constant. */
    SmsProviderType providerId();

    /**
     * @param settings the tenant's stored settings, already parsed
     * @param secrets  every credential {@link SmsProviderType#getRequiredSecretNames()} names,
     *                 already decrypted
     * @return a sender bound to that tenant's account
     * @throws org.arghyam.jalsoochak.message.exception.ProviderNotUsableException if the settings
     *         cannot produce a working sender
     */
    SmsSender create(SmsProviderSettings settings, TenantSecrets secrets);
}
