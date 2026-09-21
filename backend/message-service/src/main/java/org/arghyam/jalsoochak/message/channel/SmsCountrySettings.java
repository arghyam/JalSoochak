package org.arghyam.jalsoochak.message.channel;

/**
 * PER-TENANT-PROVIDERS: everything one {@link SmsCountryService} instance needs, assembled for it
 * by whoever builds it.
 *
 * <p>This is the adapter's construction parameter, not a stored config value — that is
 * {@link org.arghyam.jalsoochak.message.dto.SmsProviderSettings}, which carries no credentials and
 * no URL. The two fill this record from different places:
 *
 * <ul>
 *   <li>{@code SmsConfig} builds the system default entirely from {@code smscountry.*};</li>
 *   <li>{@link SmsCountrySenderFactory} takes the sender and DLT ids and the OTP text from the
 *       tenant's settings, the credentials from the encrypted secret store, and
 *       {@link #baseUrl()} from {@code smscountry.base-url} — system-wide in both paths, so a
 *       tenant setting cannot choose where its auth token is sent (O2-13).</li>
 * </ul>
 *
 * <p>{@code notifications.sms.dry-run} is not here: it is an operational kill switch over the
 * whole channel, not a property of an account, so it stays a separate constructor argument and
 * stays global (O2-12).
 *
 * @param baseUrl              SMSCountry API root, system-wide
 * @param authKey              account key, also the {@code /Accounts/{authKey}/} path segment
 * @param authToken            account token, the password half of the basic-auth pair
 * @param senderId             the registered sender id / header the message goes out under
 * @param dltPrincipalEntityId the entity the sender id is registered to on the DLT portal
 * @param dltTemplateId        the DLT registration {@code otpTemplate} must match
 * @param dltHeaderId          the DLT registration of {@code senderId}
 * @param otpTemplate          the message text, with {@code {otp}} and {@code {expiryMinutes}}
 */
public record SmsCountrySettings(
        String baseUrl,
        String authKey,
        String authToken,
        String senderId,
        String dltPrincipalEntityId,
        String dltTemplateId,
        String dltHeaderId,
        String otpTemplate) {

    /**
     * Account identity only. The key and token are credentials and the template is the body of
     * every OTP this account sends; neither belongs in a log line or an exception message (S-4).
     */
    @Override
    public String toString() {
        return "SmsCountrySettings(baseUrl=" + baseUrl + ", senderId=" + senderId + ")";
    }
}
