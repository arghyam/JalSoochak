package org.arghyam.jalsoochak.user.util;

import java.util.regex.Pattern;

public final class PhoneNumberUtil {
    private static final Pattern INDIAN_10_DIGIT = Pattern.compile("^[6-9]\\d{9}$");

    private PhoneNumberUtil() {}

    public static boolean isValidIndianMobile(String phone) {
        if (phone == null) {
            return false;
        }
        return INDIAN_10_DIGIT.matcher(phone.trim()).matches();
    }

    public static String normalizeIndianMobileForDb(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.startsWith("91") && trimmed.length() == 12) {
            return trimmed;
        }
        return "91" + trimmed;
    }
}
