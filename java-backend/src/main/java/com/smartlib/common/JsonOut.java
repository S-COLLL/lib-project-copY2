package com.smartlib.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts response values to what JSON.stringify produced in the original API:
 * dates become ISO strings in UTC, DECIMAL columns become plain numbers, NaN becomes null
 * and undefined object fields are left out.
 */
public final class JsonOut {

    private JsonOut() {
    }

    public static Object normalize(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> {
                if (val != Js.UNDEFINED) out.put(String.valueOf(k), normalize(val));
            });
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(o == Js.UNDEFINED ? null : normalize(o));
            return out;
        }
        if (v instanceof Object[] a) return normalize(List.of(a));
        if (v instanceof BigDecimal d) return Js.jsonNumber(d.doubleValue());
        if (v instanceof Double || v instanceof Float) return Js.jsonNumber(((Number) v).doubleValue());
        if (v instanceof Number) return v;
        if (v instanceof Temporal || v instanceof Date) {
            Instant i = JsDate.parse(v);
            return i == null ? null : JsDate.iso(i);
        }
        if (v == Js.UNDEFINED) return null;
        return v;
    }
}
