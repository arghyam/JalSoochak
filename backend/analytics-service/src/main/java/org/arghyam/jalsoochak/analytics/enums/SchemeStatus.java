package org.arghyam.jalsoochak.analytics.enums;

import java.util.Arrays;

public enum SchemeStatus {
    ACTIVE(1),
    INACTIVE(0);

    private final int code;

    SchemeStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static SchemeStatus fromCode(int code) {
        return Arrays.stream(values())
                .filter(s -> s.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SchemeStatus code: " + code));
    }
    
}
