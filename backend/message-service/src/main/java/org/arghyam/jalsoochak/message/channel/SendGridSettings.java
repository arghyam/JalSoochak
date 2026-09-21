package org.arghyam.jalsoochak.message.channel;

/**
 * PER-TENANT-PROVIDERS: everything one {@link SendGridMailSender} instance needs, assembled for it
 * by whoever builds it.
 *
 * <p>This is the adapter's construction parameter, not a stored config value — that is
 * {@link org.arghyam.jalsoochak.message.dto.EmailProviderSettings}, which carries no credentials
 * and no URL. The two fill this record from different places:
 *
 * <ul>
 *   <li>{@code SystemDefaultProviders} builds the system default entirely from
 *       {@code notification.mail.*};</li>
 *   <li>{@link SendGridMailSenderFactory} takes the from address, display name, logo and template
 *       ids from the tenant's settings, the API key from the encrypted secret store, and
 *       {@link #apiUrl()} from {@code notification.mail.sendgrid.api-url} — system-wide in both
 *       paths, so a tenant setting cannot choose where its API key is sent (O2-13).</li>
 * </ul>
 *
 * @param apiUrl       SendGrid API root, system-wide
 * @param apiKey       the account's API key, sent as the bearer token
 * @param fromAddress  a verified sender in the account that owns {@code apiKey}
 * @param fromName     the display name recipients see
 * @param logoImageUrl injected into every template as {@code logo_image}
 * @param templates    the dynamic template ids, which belong to the owning account
 */
public record SendGridSettings(
        String apiUrl,
        String apiKey,
        String fromAddress,
        String fromName,
        String logoImageUrl,
        Templates templates) {

    /** The five transactional templates this service sends. */
    public record Templates(
            String passwordReset,
            String reinvitation,
            String defaultInvitation,
            String superUserInvitation,
            String stateAdminInvitation) {
    }

    /**
     * Account identity only. The API key is a credential and must not reach a log line, an
     * exception message or a heap dump label (S-4).
     */
    @Override
    public String toString() {
        return "SendGridSettings(apiUrl=" + apiUrl + ", fromAddress=" + fromAddress + ")";
    }
}
