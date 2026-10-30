package com.smartlib.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON request body or a query string, read with JavaScript rules: a missing key is undefined,
 * which differs from an explicit null (Number(undefined) is NaN, Number(null) is 0).
 */
public final class Input {

    private final Map<String, Object> values;

    private Input(Map<String, Object> values) {
        this.values = values == null ? Collections.emptyMap() : values;
    }

    public static Input of(Map<String, ?> values) {
        return new Input(values == null ? null : new LinkedHashMap<>(values));
    }

    public static Input empty() {
        return new Input(null);
    }

    /** body.k !== undefined. */
    public boolean has(String k) {
        return values.containsKey(k);
    }

    /** The raw value, or Js.UNDEFINED when the key is absent. */
    public Object get(String k) {
        return values.containsKey(k) ? values.get(k) : Js.UNDEFINED;
    }

    /** The raw value, or null when absent. */
    public Object raw(String k) {
        return values.get(k);
    }

    public boolean truthy(String k) {
        return Js.truthy(get(k));
    }

    /** Number(body.k). */
    public double num(String k) {
        return Js.num(get(k));
    }

    /** String(body.k || ''). */
    public String text(String k) {
        return Js.text(get(k));
    }

    /** String(body.k). */
    public String str(String k) {
        return Js.str(get(k));
    }

    /** A nested object, or null. */
    @SuppressWarnings("unchecked")
    public Input obj(String k) {
        Object v = values.get(k);
        return v instanceof Map<?, ?> m ? Input.of((Map<String, ?>) m) : null;
    }

    /** A nested array, or null. */
    public List<?> list(String k) {
        Object v = values.get(k);
        return v instanceof List<?> l ? l : null;
    }

    public Map<String, Object> asMap() {
        return values;
    }

    /** Copy with one key changed (or added). */
    public Input with(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        if (v == Js.UNDEFINED) m.remove(k);
        else m.put(k, v);
        return new Input(m);
    }
}
