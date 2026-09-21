package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;

/**
 * PER-TENANT-PROVIDERS: builds an {@link EmailSender} for one tenant's own account (O2-2).
 *
 * <p>Today {@code notification.mail.provider} chooses one {@code EmailSender} bean for the whole
 * process through {@code @ConditionalOnProperty}, so SendGrid and SMTP can never coexist. Several
 * tenants on different providers need them both registered at once, so each adapter becomes a plain
 * class built from settings and gains a factory that is always registered; the property comes to
 * mean "the system default provider" (O2-4).
 *
 * <p>{@link SendGridMailSenderFactory} and {@link SmtpMailSenderFactory} implement it, one per
 * {@link EmailProviderType} constant. Both are registered unconditionally, because the provider a
 * tenant uses is independent of the one the platform's own account uses.
 *
 * <p>Implementations must not perform I/O in {@link #create}: it runs on the send path the first
 * time a tenant is seen, and a slow build blocks the Kafka listener thread for every tenant behind
 * it. Validation of the endpoint has already happened in {@code ProviderEndpointPolicy}.
 */
public interface EmailSenderFactory {

    /** The provider this factory builds. Exactly one factory per constant. */
    EmailProviderType providerId();

    /**
     * @param settings the tenant's stored settings, already parsed and endpoint-checked
     * @param secrets  every credential {@link EmailProviderType#getRequiredSecretNames()} names,
     *                 already decrypted; never empty for a required name
     * @return a sender bound to that tenant's account
     * @throws org.arghyam.jalsoochak.message.exception.ProviderNotUsableException if the settings
     *         cannot produce a working sender
     */
    EmailSender create(EmailProviderSettings settings, TenantSecrets secrets);
}
