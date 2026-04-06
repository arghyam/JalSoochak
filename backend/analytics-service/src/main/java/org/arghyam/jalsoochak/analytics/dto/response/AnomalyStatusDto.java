package org.arghyam.jalsoochak.analytics.dto.response;

import java.util.Arrays;

public enum AnomalyStatusDto {
    UNRESOLVED(0, "Unresolved"),
    IN_PROGRESS(1, "In-Progress"),
    RESOLVED(2, "Resolved");

    private final int code;
    private final String label;

    AnomalyStatusDto(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static AnomalyStatusDto fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(v -> v.code == code)
                .findFirst()
                .orElse(null);
    }
}

