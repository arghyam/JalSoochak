package org.arghyam.jalsoochak.message.channel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;

/**
 * PER-TENANT-PROVIDERS: the OTP text one tenant sends, checked once when its sender is built and
 * then only filled in (O2-15).
 *
 * <p>The placeholders are named — {@code {otp}} exactly once, {@code {expiryMinutes}} at most once
 * — rather than the {@code %s}/{@code %d} pair the single hard-coded template used with
 * {@code String.formatted}. A stored setting with an extra or mistyped conversion would have
 * thrown {@code IllegalFormatException} on the send path, or silently consumed the wrong argument;
 * either way the first person to find out would be a user who could not log in.
 *
 * <p>tenant-service applies these same rules when the setting is written, and checking again here
 * is deliberate, for the reason {@link ProviderEndpointPolicy} re-checks an SMTP host: a row can
 * predate a rule, or arrive by another route. A template that fails leaves the tenant on the
 * system default with an ERROR (O2-9), which is better than texting somebody {@code {foo}} — and
 * better than a message the operator drops anyway, since the text must match the tenant's DLT
 * registration character for character.
 */
public final class OtpMessageTemplate {

    /**
     * Two GSM-7 segments, as {@code SmsProviderConfigDTO.MAX_OTP_TEMPLATE_LENGTH} in tenant-service.
     * Long enough for every DLT-registered OTP text seen so far, short enough that a stored value
     * cannot quietly turn one billable SMS into ten.
     */
    public static final int MAX_LENGTH = 320;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
    private static final String OTP = "otp";
    private static final String EXPIRY_MINUTES = "expiryMinutes";

    private final String template;

    private OtpMessageTemplate(String template) {
        this.template = template;
    }

    /**
     * @param template the tenant's stored text, or the default
     * @throws ProviderNotUsableException if it is blank, too long, names a placeholder that is not
     *                                    one of the two, omits {@code {otp}} or repeats either
     */
    public static OtpMessageTemplate compile(String template) {
        if (template == null || template.isBlank()) {
            throw new ProviderNotUsableException("smscountry.otpTemplate is blank");
        }
        String text = template.trim();
        if (text.length() > MAX_LENGTH) {
            throw new ProviderNotUsableException("smscountry.otpTemplate is " + text.length()
                    + " characters, which exceeds the maximum of " + MAX_LENGTH);
        }

        int otpCount = 0;
        int expiryCount = 0;
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (OTP.equals(name)) {
                otpCount++;
            } else if (EXPIRY_MINUTES.equals(name)) {
                expiryCount++;
            } else {
                throw new ProviderNotUsableException("smscountry.otpTemplate contains unknown"
                        + " placeholder '{" + name + "}'. Supported placeholders: {" + OTP + "}, {"
                        + EXPIRY_MINUTES + "}");
            }
        }
        if (otpCount != 1) {
            throw new ProviderNotUsableException("smscountry.otpTemplate must contain {" + OTP
                    + "} exactly once, found " + otpCount);
        }
        if (expiryCount > 1) {
            throw new ProviderNotUsableException("smscountry.otpTemplate must contain {"
                    + EXPIRY_MINUTES + "} at most once, found " + expiryCount);
        }
        return new OtpMessageTemplate(text);
    }

    /**
     * The message text for one send.
     *
     * <p>One pass over the template, so a substituted value is never rescanned: an {@code otp} that
     * happened to contain {@code {expiryMinutes}} is written out as it is rather than expanded.
     * {@link Matcher#quoteReplacement} is what keeps a {@code $} or a {@code \} in a value from
     * being read as a group reference.
     */
    public String render(String otp, int expiryMinutes) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length() + 16);
        while (matcher.find()) {
            // compile() has already proved every placeholder is one of the two.
            String value = OTP.equals(matcher.group(1)) ? otp : Integer.toString(expiryMinutes);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** The template, never a rendered message: a rendered one carries a live OTP. */
    @Override
    public String toString() {
        return template;
    }
}
