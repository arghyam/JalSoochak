package org.arghyam.jalsoochak.message.util;

import java.util.regex.Pattern;

/**
 * Masks anything that looks like a phone number before it reaches a log line or a stored payload.
 *
 * <p>Phone numbers are PII and must not appear above {@code DEBUG} (see {@code CLAUDE.md}). Two
 * places hand us text that contains them without asking:</p>
 * <ul>
 *   <li>Kafka notification payloads, which carry operator and officer mobile numbers.</li>
 *   <li><strong>Glific's {@code errors} blob.</strong> A Gupshup/Meta delivery failure arrives as
 *       {@code {"payload":{"payload":{"reason":…,"code":…},"destination":"91XXXXXXXXXX"}}} — the
 *       recipient's raw number sits in {@code destination}. Anything that logs or persists that blob
 *       whole leaks it.</li>
 * </ul>
 *
 * <p>Deliberately blunt: a run of ten or more digits is masked, which covers both the bare 10-digit
 * mobile and the {@code 91XXXXXXXXXX} E.164 form, and will also mask a long numeric id. That is the
 * right trade — over-masking a debug payload costs nothing, under-masking leaks PII.</p>
 */
public final class PhoneRedactor {

    private PhoneRedactor() {
    }

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{10,}");

    /**
     * Replaces every run of 10+ digits with asterisks, keeping the last four so two records can still
     * be told apart.
     *
     * @param payload any object; {@code null} in, {@code null} out
     */
    public static String redact(Object payload) {
        if (payload == null) {
            return null;
        }
        return DIGIT_RUN.matcher(payload.toString()).replaceAll(m -> "*".repeat(m.group().length() - 4)
                + m.group().substring(m.group().length() - 4));
    }
}
