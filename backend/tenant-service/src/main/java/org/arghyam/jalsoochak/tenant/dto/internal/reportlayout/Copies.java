package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unmodifiable copies that, unlike {@code List.copyOf}, keep {@code null}: an absent list stays
 * absent in the stored JSON, and a {@code null} element reaches the validator, which reports it with
 * its path instead of failing the bind.
 */
final class Copies {

    private Copies() {
    }

    static <T> List<T> list(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** Keeps the document's order: a report block's roles are its recipients, in that order. */
    static <K, V> Map<K, V> map(Map<K, V> map) {
        return map == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
