package com.fleetops.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered before/after snapshots for audit entries; unlike Map.of it accepts null values. */
public final class AuditFields {

    private AuditFields() {
    }

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            map.put(String.valueOf(keyValues[i]), value instanceof Enum<?> e ? e.name() : value);
        }
        return map;
    }
}
